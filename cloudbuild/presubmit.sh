#!/bin/bash

# Copyright 2019 Google Inc. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#            http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -euxo pipefail

readonly HADOOP_PROFILE="$1"

cd /bigdata-interop

# Print Maven info
./mvnw -v

# Run all tests
./mvnw -B -e -T1C "-P${HADOOP_PROFILE}" -Pcoverage -DargLine="-mx3g" -Dtest="*" clean test

# Upload test coverage report only for Hadoop 2
if [[ ${HADOOP_PROFILE} == "hadoop2" ]]; then
  bash <(curl -s https://codecov.io/bash)
fi

# Run integration tests
#mvn -B -e "-P${HADOOP_PROFILE}" -Pintegration-test -DargLine="-mx3g" clean test
