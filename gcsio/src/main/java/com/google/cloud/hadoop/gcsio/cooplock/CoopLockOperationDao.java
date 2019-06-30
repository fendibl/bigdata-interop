/*
 * Copyright 2019 Google LLC. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.gcsio.cooplock;

import static com.google.cloud.hadoop.gcsio.CreateObjectOptions.DEFAULT_CONTENT_TYPE;
import static com.google.cloud.hadoop.gcsio.CreateObjectOptions.EMPTY_METADATA;
import static com.google.cloud.hadoop.gcsio.cooplock.CoopLockRecordsDao.LOCK_DIRECTORY;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.cloud.hadoop.gcsio.CreateObjectOptions;
import com.google.cloud.hadoop.gcsio.FileInfo;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorage;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageItemInfo;
import com.google.cloud.hadoop.gcsio.PathCodec;
import com.google.cloud.hadoop.gcsio.StorageResourceId;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.common.flogger.GoogleLogger;
import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CoopLockOperationDao {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final Set<String> VALID_OPERATIONS = ImmutableSet.of("delete", "rename");
  private static final String OPERATION_LOG_FILE_FORMAT = "%s_%s_%s.log";
  private static final String OPERATION_LOCK_FILE_FORMAT = "%s_%s_%s.lock";
  private static final CreateObjectOptions CREATE_OBJECT_OPTIONS =
      new CreateObjectOptions(/* overwriteExisting= */ false, "application/text", EMPTY_METADATA);
  private static final CreateObjectOptions UPDATE_OBJECT_OPTIONS =
      new CreateObjectOptions(/* overwriteExisting= */ true, "application/text", EMPTY_METADATA);

  private static DateTimeFormatter LOCK_FILE_DATE_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSXXX").withZone(ZoneId.of("UTC"));

  private static final Gson GSON = new Gson();

  private GoogleCloudStorage gcs;
  private PathCodec pathCodec;

  public CoopLockOperationDao(GoogleCloudStorage gcs, PathCodec pathCodec) {
    this.gcs = gcs;
    this.pathCodec = pathCodec;
  }

  public Future<?> persistDeleteOperation(
      String operationId,
      Instant operationInstant,
      StorageResourceId resourceId,
      List<FileInfo> itemsToDelete,
      List<FileInfo> bucketsToDelete)
      throws IOException {
    URI operationLockPath =
        writeOperationFile(
            resourceId.getBucketName(),
            OPERATION_LOCK_FILE_FORMAT,
            CREATE_OBJECT_OPTIONS,
            "delete",
            operationId,
            operationInstant,
            ImmutableList.of(
                GSON.toJson(
                    new DeleteOperation()
                        .setLockEpochSeconds(operationInstant.getEpochSecond())
                        .setResource(resourceId.toString()))));
    List<String> logRecords =
        Streams.concat(itemsToDelete.stream(), bucketsToDelete.stream())
            .map(i -> i.getItemInfo().getResourceId().toString())
            .collect(toImmutableList());
    writeOperationFile(
        resourceId.getBucketName(),
        OPERATION_LOG_FILE_FORMAT,
        CREATE_OBJECT_OPTIONS,
        "delete",
        operationId,
        operationInstant,
        logRecords);
    // Schedule lock expiration update
    return scheduleLockUpdate(
        operationId,
        operationLockPath,
        DeleteOperation.class,
        (o, i) -> o.setLockEpochSeconds(i.getEpochSecond()));
  }

  public Future<?> persistUpdateOperation(
      String operationId,
      Instant operationInstant,
      StorageResourceId src,
      StorageResourceId dst,
      Map<FileInfo, URI> srcToDstItemNames,
      Map<FileInfo, URI> srcToDstMarkerItemNames)
      throws IOException {
    URI operationLockPath =
        writeOperationFile(
            dst.getBucketName(),
            OPERATION_LOCK_FILE_FORMAT,
            CREATE_OBJECT_OPTIONS,
            "rename",
            operationId,
            operationInstant,
            ImmutableList.of(
                GSON.toJson(
                    new RenameOperation()
                        .setLockEpochSeconds(operationInstant.getEpochSecond())
                        .setSrcResource(src.toString())
                        .setDstResource(dst.toString())
                        .setCopySucceeded(false))));
    List<String> logRecords =
        Streams.concat(
                srcToDstItemNames.entrySet().stream(), srcToDstMarkerItemNames.entrySet().stream())
            .map(e -> e.getKey().getItemInfo().getResourceId() + " -> " + e.getValue())
            .collect(toImmutableList());
    writeOperationFile(
        dst.getBucketName(),
        OPERATION_LOG_FILE_FORMAT,
        CREATE_OBJECT_OPTIONS,
        "rename",
        operationId,
        operationInstant,
        logRecords);
    // Schedule lock expiration update
    return scheduleLockUpdate(
        operationId,
        operationLockPath,
        RenameOperation.class,
        (o, i) -> o.setLockEpochSeconds(i.getEpochSecond()));
  }

  public void checkpointUpdateOperation(
      StorageResourceId src, StorageResourceId dst, String operationId, Instant operationInstant)
      throws IOException {
    writeOperationFile(
        dst.getBucketName(),
        OPERATION_LOCK_FILE_FORMAT,
        UPDATE_OBJECT_OPTIONS,
        "rename",
        operationId,
        operationInstant,
        ImmutableList.of(
            GSON.toJson(
                new RenameOperation()
                    .setLockEpochSeconds(Instant.now().getEpochSecond())
                    .setSrcResource(src.toString())
                    .setDstResource(dst.toString())
                    .setCopySucceeded(true))));
  }

  private void renewLockOrExit(
      String operationId, URI operationLockPath, Function<String, String> renewFn) {
    // read lock file info
    for (int i = 0; i < 10; i++) {
      try {
        renewLock(operationId, operationLockPath, renewFn);
      } catch (IOException e) {
        logger.atWarning().withCause(e).log(
            "Failed to renew '%s' lock for %s operation, retry #%d",
            operationLockPath, operationId, i + 1);
      }
      sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
    }
    logger.atSevere().log(
        "Failed to renew '%s' lock for %s operation, exiting", operationLockPath, operationId);
    System.exit(1);
  }

  private void renewLock(
      String operationId, URI operationLockPath, Function<String, String> renewFn)
      throws IOException {
    StorageResourceId lockId = StorageResourceId.fromObjectName(operationLockPath.toString());
    GoogleCloudStorageItemInfo lockInfo = gcs.getItemInfo(lockId);
    checkState(lockInfo.exists(), "lock file for %s operation should exist", operationId);

    String lock;
    try (BufferedReader reader =
        new BufferedReader(Channels.newReader(gcs.open(lockId), UTF_8.name()))) {
      lock = reader.lines().collect(Collectors.joining());
    }

    lock = renewFn.apply(lock);
    CreateObjectOptions updateOptions =
        new CreateObjectOptions(
            /* overwriteExisting= */ true, DEFAULT_CONTENT_TYPE, EMPTY_METADATA);
    StorageResourceId operationLockPathResourceId =
        new StorageResourceId(
            operationLockPath.getAuthority(),
            operationLockPath.getPath(),
            lockInfo.getContentGeneration());
    writeOperation(operationLockPathResourceId, updateOptions, ImmutableList.of(lock));
  }

  private URI writeOperationFile(
      String bucket,
      String fileNameFormat,
      CreateObjectOptions createObjectOptions,
      String operation,
      String operationId,
      Instant operationInstant,
      List<String> records)
      throws IOException {
    checkArgument(
        VALID_OPERATIONS.contains(operation),
        "operation must be one of $s, but was '%s'",
        VALID_OPERATIONS,
        operation);
    String date = LOCK_FILE_DATE_TIME_FORMAT.format(operationInstant);
    String file = String.format(LOCK_DIRECTORY + fileNameFormat, date, operation, operationId);
    URI path = pathCodec.getPath(bucket, file, /* allowEmptyObjectName= */ false);
    StorageResourceId resourceId =
        pathCodec.validatePathAndGetId(path, /* allowEmptyObjectName= */ false);
    writeOperation(resourceId, createObjectOptions, records);
    return path;
  }

  private void writeOperation(
      StorageResourceId resourceId, CreateObjectOptions createObjectOptions, List<String> records)
      throws IOException {
    try (WritableByteChannel channel = gcs.create(resourceId, createObjectOptions)) {
      for (String record : records) {
        channel.write(ByteBuffer.wrap(record.getBytes(UTF_8)));
        channel.write(ByteBuffer.wrap(new byte[] {'\n'}));
      }
    }
  }

  private ScheduledExecutorService scheduledThreadPool = Executors.newScheduledThreadPool(1);

  public <T> Future<?> scheduleLockUpdate(
      String operationId, URI operationLockPath, Class<T> clazz, BiConsumer<T, Instant> renewFn) {
    return scheduledThreadPool.scheduleAtFixedRate(
        () ->
            renewLockOrExit(
                operationId,
                operationLockPath,
                l -> {
                  T operation = GSON.fromJson(l, clazz);
                  renewFn.accept(operation, Instant.now());
                  return GSON.toJson(operation);
                }),
        /* initialDelay= */ 1,
        /* period= */ 1,
        TimeUnit.MINUTES);
  }
}