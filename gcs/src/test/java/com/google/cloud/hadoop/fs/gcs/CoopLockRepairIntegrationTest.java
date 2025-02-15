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

package com.google.cloud.hadoop.fs.gcs;

import static com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystemBase.AUTHENTICATION_PREFIX;
import static com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystemConfiguration.GCS_COOPERATIVE_LOCKING_EXPIRATION_TIMEOUT_MS;
import static com.google.cloud.hadoop.gcsio.cooplock.CoopLockRecordsDao.LOCK_DIRECTORY;
import static com.google.cloud.hadoop.util.EntriesCredentialConfiguration.ENABLE_SERVICE_ACCOUNTS_SUFFIX;
import static com.google.cloud.hadoop.util.EntriesCredentialConfiguration.SERVICE_ACCOUNT_EMAIL_SUFFIX;
import static com.google.cloud.hadoop.util.EntriesCredentialConfiguration.SERVICE_ACCOUNT_KEYFILE_SUFFIX;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertThrows;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.HttpExecuteInterceptor;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.cloud.hadoop.gcsio.FileInfo;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageFileSystem;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageFileSystemIntegrationHelper;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageFileSystemOptions;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageImpl;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageIntegrationHelper;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageOptions;
import com.google.cloud.hadoop.gcsio.cooplock.DeleteOperation;
import com.google.cloud.hadoop.gcsio.cooplock.RenameOperation;
import com.google.cloud.hadoop.gcsio.integration.GoogleCloudStorageTestHelper;
import com.google.cloud.hadoop.gcsio.testing.TestConfiguration;
import com.google.cloud.hadoop.util.RetryHttpInitializer;
import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.hadoop.conf.Configuration;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Integration tests for GoogleCloudStorageFileSystem class. */
@RunWith(JUnit4.class)
public class CoopLockRepairIntegrationTest {

  private static final Gson GSON = new Gson();

  private static final String OPERATION_FILENAME_PATTERN =
      "[0-9]{8}T[0-9]{6}\\.[0-9]{3}Z_%s_[a-z0-9\\-]+";

  private static GoogleCloudStorageOptions gcsOptions;
  private static RetryHttpInitializer httpRequestInitializer;
  private static GoogleCloudStorageFileSystemIntegrationHelper gcsfsIHelper;

  @BeforeClass
  public static void before() throws Throwable {
    String projectId =
        checkNotNull(TestConfiguration.getInstance().getProjectId(), "projectId can not be null");
    String appName = GoogleCloudStorageIntegrationHelper.APP_NAME;
    Credential credential =
        checkNotNull(GoogleCloudStorageTestHelper.getCredential(), "credential must not be null");

    gcsOptions =
        GoogleCloudStorageOptions.newBuilder().setAppName(appName).setProjectId(projectId).build();
    httpRequestInitializer =
        new RetryHttpInitializer(
            credential,
            gcsOptions.getAppName(),
            gcsOptions.getMaxHttpRequestRetries(),
            gcsOptions.getHttpRequestConnectTimeout(),
            gcsOptions.getHttpRequestReadTimeout());

    GoogleCloudStorageFileSystem gcsfs =
        new GoogleCloudStorageFileSystem(
            credential,
            GoogleCloudStorageFileSystemOptions.newBuilder()
                .setEnableBucketDelete(true)
                .setCloudStorageOptionsBuilder(gcsOptions.toBuilder())
                .build());

    gcsfsIHelper = new GoogleCloudStorageFileSystemIntegrationHelper(gcsfs);
    gcsfsIHelper.beforeAllTests();
  }

  @AfterClass
  public static void afterClass() throws Throwable {
    gcsfsIHelper.afterAllTests();
    GoogleCloudStorageFileSystem gcsfs = gcsfsIHelper.gcsfs;
    assertThat(gcsfs.exists(new URI("gs://" + gcsfsIHelper.sharedBucketName1))).isFalse();
    assertThat(gcsfs.exists(new URI("gs://" + gcsfsIHelper.sharedBucketName2))).isFalse();
  }

  @Test
  public void moveDirectoryOperationRolledForwardAfterFailedCopy() throws Exception {
    moveDirectoryOperationRepairedAfterFailedCopy("--rollForward");
  }

  @Test
  public void moveDirectoryOperationRolledBackAfterFailedCopy() throws Exception {
    moveDirectoryOperationRepairedAfterFailedCopy("--rollBack");
  }

  private void moveDirectoryOperationRepairedAfterFailedCopy(String command) throws Exception {
    String bucketName =
        gcsfsIHelper.createUniqueBucket(
            "cooperative-rename-" + command.toLowerCase().replace("--roll", "") + "-failed-move");
    URI bucketUri = new URI("gs://" + bucketName + "/");
    String dirName = "rename_" + UUID.randomUUID();
    String fileName = "file";
    URI srcDirUri = bucketUri.resolve(dirName + "_src/");
    URI dstDirUri = bucketUri.resolve(dirName + "_dst/");

    // create file to rename
    gcsfsIHelper.writeTextFile(bucketName, srcDirUri.resolve(fileName).getPath(), "file_content");

    GoogleCloudStorageFileSystemOptions gcsFsOptions = newGcsFsOptions();
    HttpRequestInitializer failingRequestInitializer =
        request -> {
          httpRequestInitializer.initialize(request);
          HttpExecuteInterceptor executeInterceptor = checkNotNull(request.getInterceptor());
          request.setInterceptor(
              r -> {
                executeInterceptor.intercept(r);
                if ("POST".equals(request.getRequestMethod())
                    && request.getUrl().toString().contains("/copyTo/")) {
                  throw new RuntimeException("Injected failure");
                }
              });
        };
    GoogleCloudStorageFileSystem failingGcsFs = newGcsFs(gcsFsOptions, failingRequestInitializer);

    RuntimeException e =
        assertThrows(RuntimeException.class, () -> failingGcsFs.rename(srcDirUri, dstDirUri));
    assertThat(e).hasCauseThat().hasMessageThat().endsWith("Injected failure");

    GoogleCloudStorageFileSystem gcsFs = newGcsFs(gcsFsOptions, httpRequestInitializer);

    assertThat(gcsFs.exists(srcDirUri)).isTrue();
    assertThat(gcsFs.exists(srcDirUri.resolve(fileName))).isTrue();
    assertThat(gcsFs.exists(dstDirUri)).isTrue();
    assertThat(gcsFs.exists(dstDirUri.resolve(fileName))).isFalse();

    CoopLockFsck fsck = new CoopLockFsck();
    fsck.setConf(getTestConfiguration());

    fsck.run(new String[] {command, "gs://" + bucketName});

    URI deletedDirUri = "--rollForward".equals(command) ? srcDirUri : dstDirUri;
    URI repairedDirUri = "--rollForward".equals(command) ? dstDirUri : srcDirUri;
    assertThat(gcsFs.exists(deletedDirUri)).isFalse();
    assertThat(gcsFs.exists(deletedDirUri.resolve(fileName))).isFalse();
    assertThat(gcsFs.exists(repairedDirUri)).isTrue();
    assertThat(gcsFs.exists(repairedDirUri.resolve(fileName))).isTrue();

    // Validate lock files
    List<URI> lockFiles =
        gcsFs.listFileInfo(bucketUri.resolve(LOCK_DIRECTORY)).stream()
            .map(FileInfo::getPath)
            .collect(toList());

    assertThat(lockFiles).hasSize(2);
    URI lockFileUri =
        matchFile(lockFiles, String.format(OPERATION_FILENAME_PATTERN, "rename") + "\\.lock").get();
    URI logFileUri =
        matchFile(lockFiles, String.format(OPERATION_FILENAME_PATTERN, "rename") + "\\.log").get();

    String lockContent = gcsfsIHelper.readTextFile(bucketName, lockFileUri.getPath());
    assertThat(GSON.fromJson(lockContent, RenameOperation.class).setLockEpochSeconds(0))
        .isEqualTo(
            new RenameOperation()
                .setLockEpochSeconds(0)
                .setSrcResource(srcDirUri.toString())
                .setDstResource(dstDirUri.toString())
                .setCopySucceeded(false));
    assertThat(gcsfsIHelper.readTextFile(bucketName, logFileUri.getPath()))
        .isEqualTo(srcDirUri.resolve(fileName) + " -> " + dstDirUri.resolve(fileName) + "\n");
  }

  @Test
  public void moveDirectoryOperationRepairedAfterFailedDelete() throws Exception {
    String bucketName = gcsfsIHelper.createUniqueBucket("cooperative-rename-repair-failed-delete");
    URI bucketUri = new URI("gs://" + bucketName + "/");
    String dirName = "rename_" + UUID.randomUUID();
    String fileName = "file";
    URI srcDirUri = bucketUri.resolve(dirName + "_src/");
    URI dstDirUri = bucketUri.resolve(dirName + "_dst/");

    // create file to rename
    gcsfsIHelper.writeTextFile(bucketName, srcDirUri.resolve(fileName).getPath(), "file_content");

    GoogleCloudStorageFileSystemOptions gcsFsOptions = newGcsFsOptions();
    HttpRequestInitializer failingRequestInitializer =
        request -> {
          httpRequestInitializer.initialize(request);
          HttpExecuteInterceptor executeInterceptor = checkNotNull(request.getInterceptor());
          request.setInterceptor(
              r -> {
                executeInterceptor.intercept(r);
                if ("DELETE".equals(request.getRequestMethod())
                    && request.getUrl().toString().contains("/b/" + bucketName + "/o/")) {
                  throw new RuntimeException("Injected failure");
                }
              });
        };
    GoogleCloudStorageFileSystem failingGcsFs = newGcsFs(gcsFsOptions, failingRequestInitializer);

    RuntimeException e =
        assertThrows(RuntimeException.class, () -> failingGcsFs.rename(srcDirUri, dstDirUri));
    assertThat(e).hasCauseThat().hasMessageThat().endsWith("Injected failure");

    GoogleCloudStorageFileSystem gcsFs = newGcsFs(gcsFsOptions, httpRequestInitializer);

    assertThat(gcsFs.exists(srcDirUri)).isTrue();
    assertThat(gcsFs.exists(srcDirUri.resolve(fileName))).isTrue();
    assertThat(gcsFs.exists(dstDirUri)).isTrue();
    assertThat(gcsFs.exists(dstDirUri.resolve(fileName))).isTrue();

    CoopLockFsck fsck = new CoopLockFsck();
    fsck.setConf(getTestConfiguration());

    fsck.run(new String[] {"--rollForward", "gs://" + bucketName});

    assertThat(gcsFs.exists(srcDirUri)).isFalse();
    assertThat(gcsFs.exists(srcDirUri.resolve(fileName))).isFalse();
    assertThat(gcsFs.exists(dstDirUri)).isTrue();
    assertThat(gcsFs.exists(dstDirUri.resolve(fileName))).isTrue();

    // Validate lock files
    List<URI> lockFiles =
        gcsFs.listFileInfo(bucketUri.resolve(LOCK_DIRECTORY)).stream()
            .map(FileInfo::getPath)
            .collect(toList());

    assertThat(lockFiles).hasSize(2);
    URI lockFileUri =
        matchFile(lockFiles, String.format(OPERATION_FILENAME_PATTERN, "rename") + "\\.lock").get();
    URI logFileUri =
        matchFile(lockFiles, String.format(OPERATION_FILENAME_PATTERN, "rename") + "\\.log").get();

    String lockContent = gcsfsIHelper.readTextFile(bucketName, lockFileUri.getPath());
    assertThat(GSON.fromJson(lockContent, RenameOperation.class).setLockEpochSeconds(0))
        .isEqualTo(
            new RenameOperation()
                .setLockEpochSeconds(0)
                .setSrcResource(srcDirUri.toString())
                .setDstResource(dstDirUri.toString())
                .setCopySucceeded(true));
    assertThat(gcsfsIHelper.readTextFile(bucketName, logFileUri.getPath()))
        .isEqualTo(srcDirUri.resolve(fileName) + " -> " + dstDirUri.resolve(fileName) + "\n");
  }

  @Test
  public void deleteDirectoryOperationRolledForward() throws Exception {
    String bucketName = gcsfsIHelper.createUniqueBucket("cooperative-delete-repair");
    URI bucketUri = new URI("gs://" + bucketName + "/");
    String fileName = "file";
    URI dirUri = bucketUri.resolve("delete_" + UUID.randomUUID() + "/");

    // create file to delete
    gcsfsIHelper.writeTextFile(bucketName, dirUri.resolve(fileName).getPath(), "file_content");

    GoogleCloudStorageFileSystemOptions gcsFsOptions = newGcsFsOptions();
    HttpRequestInitializer failingRequestInitializer =
        request -> {
          httpRequestInitializer.initialize(request);
          HttpExecuteInterceptor executeInterceptor = checkNotNull(request.getInterceptor());
          request.setInterceptor(
              r -> {
                executeInterceptor.intercept(r);
                if ("DELETE".equals(request.getRequestMethod())
                    && request.getUrl().toString().contains("/b/" + bucketName + "/o/")) {
                  throw new RuntimeException("Injected failure");
                }
              });
        };
    GoogleCloudStorageFileSystem failingGcsFs = newGcsFs(gcsFsOptions, failingRequestInitializer);

    RuntimeException e =
        assertThrows(
            RuntimeException.class, () -> failingGcsFs.delete(dirUri, /* recursive= */ true));
    assertThat(e).hasCauseThat().hasMessageThat().endsWith("Injected failure");

    GoogleCloudStorageFileSystem gcsFs = newGcsFs(gcsFsOptions, httpRequestInitializer);

    assertThat(gcsFs.exists(dirUri)).isTrue();
    assertThat(gcsFs.exists(dirUri.resolve(fileName))).isTrue();

    CoopLockFsck fsck = new CoopLockFsck();
    fsck.setConf(getTestConfiguration());

    fsck.run(new String[] {"--rollForward", "gs://" + bucketName});

    assertThat(gcsFs.exists(dirUri)).isFalse();
    assertThat(gcsFs.exists(dirUri.resolve(fileName))).isFalse();

    // Validate lock files
    List<URI> lockFiles =
        gcsFs.listFileInfo(bucketUri.resolve(LOCK_DIRECTORY)).stream()
            .map(FileInfo::getPath)
            .collect(toList());

    assertThat(lockFiles).hasSize(2);
    URI lockFileUri =
        matchFile(lockFiles, String.format(OPERATION_FILENAME_PATTERN, "delete") + "\\.lock").get();
    String lockContent = gcsfsIHelper.readTextFile(bucketName, lockFileUri.getPath());
    assertThat(GSON.fromJson(lockContent, DeleteOperation.class).setLockEpochSeconds(0))
        .isEqualTo(new DeleteOperation().setLockEpochSeconds(0).setResource(dirUri.toString()));
  }

  private Configuration getTestConfiguration() {
    Configuration conf = new Configuration();
    conf.setBoolean(AUTHENTICATION_PREFIX + ENABLE_SERVICE_ACCOUNTS_SUFFIX, true);
    conf.set(
        GoogleHadoopFileSystemConfiguration.GCS_PROJECT_ID.getKey(),
        TestConfiguration.getInstance().getProjectId());
    conf.set(
        AUTHENTICATION_PREFIX + SERVICE_ACCOUNT_EMAIL_SUFFIX,
        TestConfiguration.getInstance().getServiceAccount());
    conf.set(
        AUTHENTICATION_PREFIX + SERVICE_ACCOUNT_KEYFILE_SUFFIX,
        TestConfiguration.getInstance().getPrivateKeyFile());
    conf.setLong(GCS_COOPERATIVE_LOCKING_EXPIRATION_TIMEOUT_MS.getKey(), 0);
    return conf;
  }

  private Optional<URI> matchFile(List<URI> files, String pattern) {
    return files.stream().filter(f -> f.toString().matches("^gs://.*/" + pattern + "$")).findAny();
  }

  private GoogleCloudStorageFileSystemOptions newGcsFsOptions() {
    return GoogleCloudStorageFileSystemOptions.newBuilder()
        .setCloudStorageOptionsBuilder(gcsOptions.toBuilder())
        .setEnableCooperativeLocking(true)
        .build();
  }

  private GoogleCloudStorageFileSystem newGcsFs(
      GoogleCloudStorageFileSystemOptions gcsfsOptions, HttpRequestInitializer requestInitializer)
      throws IOException {
    GoogleCloudStorageImpl gcs = new GoogleCloudStorageImpl(gcsOptions, requestInitializer);
    return new GoogleCloudStorageFileSystem(gcs, gcsfsOptions);
  }
}
