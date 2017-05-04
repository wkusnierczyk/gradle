/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.caching.local.internal;

import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import org.apache.commons.io.FileUtils;
import org.gradle.api.Action;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.cache.PersistentCache;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.progress.BuildOperationDescriptor;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class DirectoryBuildCacheCleanup implements Action<PersistentCache> {
    private static final Logger LOGGER = Logging.getLogger(DirectoryBuildCacheCleanup.class);
    private static final Comparator<File> NEWEST_FIRST = new Comparator<File>() {
        @Override
        public int compare(File o1, File o2) {
            // Sort with the oldest last
            return Ordering.natural().compare(o2.lastModified(), o1.lastModified());
        }
    };

    private final BuildOperationExecutor buildOperationExecutor;
    private final long targetSizeInMB;

    DirectoryBuildCacheCleanup(BuildOperationExecutor buildOperationExecutor, long targetSizeInMB) {
        this.buildOperationExecutor = buildOperationExecutor;
        this.targetSizeInMB = targetSizeInMB;
    }

    @Override
    public void execute(final PersistentCache persistentCache) {
        buildOperationExecutor.run(new RunnableBuildOperation() {
            @Override
            public void run(BuildOperationContext context) {
                cleanup(persistentCache);
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor.displayName("Cleaning up " + persistentCache);
            }
        });

    }

    private void cleanup(final PersistentCache persistentCache) {
        final File[] filesEligibleForCleanup = buildOperationExecutor.call(new CallableBuildOperation<File[]>() {
            @Override
            public File[] call(BuildOperationContext context) {
                return findEligibleFiles(persistentCache.getBaseDir());
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor.displayName("Scanning " + persistentCache.getBaseDir());
            }
        });

        final List<File> filesForDeletion = buildOperationExecutor.call(new CallableBuildOperation<List<File>>() {
            @Override
            public List<File> call(BuildOperationContext context) {
                return findFilesToDelete(persistentCache, filesEligibleForCleanup);
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor.displayName("Choosing files to delete from " + persistentCache);
            }
        });

        buildOperationExecutor.run(new RunnableBuildOperation() {
            @Override
            public void run(BuildOperationContext context) {
                cleanupFiles(persistentCache, filesForDeletion);
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor.displayName("Deleting files for " + persistentCache);
            }
        });
    }

    List<File> findFilesToDelete(final PersistentCache persistentCache, File[] filesEligibleForCleanup) {
        Arrays.sort(filesEligibleForCleanup, NEWEST_FIRST);

        // All sizes are in bytes
        long totalSize = 0;
        long targetSize = targetSizeInMB * 1024 * 1024;
        final List<File> filesForDeletion = Lists.newArrayList();

        for (File file : filesEligibleForCleanup) {
            long size = file.length();
            totalSize += size;

            if (totalSize > targetSize) {
                filesForDeletion.add(file);
            }
        }

        LOGGER.info("{} consuming {} MB (target: {} MB).", persistentCache, FileUtils.byteCountToDisplaySize(totalSize), targetSizeInMB);

        return filesForDeletion;
    }

    File[] findEligibleFiles(File cacheDir) {
        return cacheDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return canBeDeleted(name);
            }
        });
    }

    void cleanupFiles(final PersistentCache persistentCache, final List<File> filesForDeletion) {
        if (!filesForDeletion.isEmpty()) {
            // Need to remove some files
            long removedSize = deleteFile(filesForDeletion);
            LOGGER.info("{} removing {} cache entries ({} MB reclaimed).", persistentCache, filesForDeletion.size(), FileUtils.byteCountToDisplaySize(removedSize));
        }
    }

    private long deleteFile(List<File> files) {
        long removedSize = 0;
        for (File file : files) {
            try {
                if (file.delete()) {
                    removedSize += file.length();
                }
            } catch (Exception e) {
                LOGGER.debug("Could not clean up cache " + file, e);
            }
        }
        return removedSize;
    }

    boolean canBeDeleted(String name) {
        return !(name.endsWith(".properties") || name.endsWith(".lock"));
    }
}