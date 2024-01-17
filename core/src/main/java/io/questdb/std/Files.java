/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.std;

import io.questdb.cairo.CairoException;
import io.questdb.std.str.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class Files {
    // The default varies across kernel versions and distros, so we use the same value as for vm.max_map_count.
    public static final long DEFAULT_FILE_LIMIT = 65536;
    public static final long DEFAULT_MAP_COUNT_LIMIT = 65536;
    public static final int DT_DIR = 4;
    public static final int DT_FILE = 8;
    public static final int DT_LNK = 10; // soft link
    public static final int DT_UNKNOWN = 0;
    public static final int FILES_RENAME_ERR_EXDEV = 1;
    public static final int FILES_RENAME_ERR_OTHER = 2;
    public static final int FILES_RENAME_OK = 0;
    public static final int MAP_RO = 1;
    public static final int MAP_RW = 2;
    public static final long PAGE_SIZE;
    public static final int POSIX_FADV_RANDOM;
    public static final int POSIX_FADV_SEQUENTIAL;
    // Apart from obvious random read use case, MADV_RANDOM/FADV_RANDOM should be used for write-only
    // append-only files. Otherwise, OS starts reading adjacent pages under memory pressure generating
    // wasted disk read ops.
    public static final int POSIX_MADV_RANDOM;
    public static final int POSIX_MADV_SEQUENTIAL;
    public static final char SEPARATOR;
    public static final Charset UTF_8;
    public static final int WINDOWS_ERROR_FILE_EXISTS = 0x50;
    private static final AtomicInteger OPEN_FILE_COUNT = new AtomicInteger();
    private static final int VIRTIO_FS_MAGIC = 0x6a656a63;
    public static boolean VIRTIO_FS_DETECTED = false;
    static IntHashSet openFds;

    private Files() {
        // Prevent construction.
    }

    public native static boolean allocate(int fd, long size);

    public native static long append(int fd, long address, long len);

    public static synchronized boolean auditClose(int fd) {
        if (fd < 0) {
            throw new IllegalStateException("Invalid fd " + fd);
        }
        if (openFds.remove(fd) == -1) {
            throw new IllegalStateException("fd " + fd + " is already closed!");
        }
        return true;
    }

    public static synchronized boolean auditOpen(int fd) {
        if (openFds == null) {
            openFds = new IntHashSet();
        }
        if (fd < 0) {
            throw new IllegalStateException("Invalid fd " + fd);
        }
        if (openFds.contains(fd)) {
            throw new IllegalStateException("fd " + fd + " is already open");
        }
        openFds.add(fd);
        return true;
    }

    public static int bumpFileCount(int fd) {
        if (fd != -1) {
            //noinspection AssertWithSideEffects
            assert auditOpen(fd);
            OPEN_FILE_COUNT.incrementAndGet();
        }
        return fd;
    }

    public static long ceilPageSize(long size) {
        return ((size + PAGE_SIZE - 1) / PAGE_SIZE) * PAGE_SIZE;
    }

    public static int close(int fd) {
        // do not close `stdin` and `stdout`
        if (fd > 1) {
            assert auditClose(fd);
            int res = close0(fd);
            if (res == 0) {
                OPEN_FILE_COUNT.decrementAndGet();
            }
            return res;
        }
        // failed to close
        return -1;
    }

    public static native int copy(long from, long to);

    public static int copy(LPSZ from, LPSZ to) {
        return copy(from.ptr(), to.ptr());
    }

    public static native long copyData(int srcFd, int destFd, long offsetSrc, long length);

    public static native long copyDataToOffset(int srcFd, int destFd, long offsetSrc, long offsetDest, long length);

    /**
     * close(fd) should be used instead of this method in most cases
     * unless you don't need close() sys call to happen.
     *
     * @param fd file descriptor
     */
    public static void decrementFileCount(int fd) {
        assert auditClose(fd);
        OPEN_FILE_COUNT.decrementAndGet();
    }

    public static native boolean exists(int fd);

    public static boolean exists(LPSZ lpsz) {
        return lpsz != null && exists0(lpsz.ptr());
    }

    public static void fadvise(int fd, long offset, long len, int advise) {
        if (Os.type == Os.LINUX_AMD64 || Os.type == Os.LINUX_ARM64) {
            fadvise0(fd, offset, len, advise);
        }
    }

    public static native void fadvise0(int fd, long offset, long len, int advise);

    public native static void findClose(long findPtr);

    public static long findFirst(LPSZ lpsz) {
        return findFirst(lpsz.ptr());
    }

    public native static long findName(long findPtr);

    public native static int findNext(long findPtr);

    public native static int findType(long findPtr);

    public static long floorPageSize(long size) {
        return size - size % PAGE_SIZE;
    }

    public static native int fsync(int fd);

    public static long getDirSize(Path path) {
        long pathUtf8Ptr = path.ptr();
        long pFind = findFirst(pathUtf8Ptr);
        if (pFind > 0L) {
            int len = path.size();
            try {
                long totalSize = 0L;
                do {
                    long nameUtf8Ptr = findName(pFind);
                    path.trimTo(len).concat(nameUtf8Ptr).$();
                    if (findType(pFind) == Files.DT_FILE) {
                        totalSize += length(path);
                    } else if (notDots(nameUtf8Ptr)) {
                        totalSize += getDirSize(path);
                    }
                }
                while (findNext(pFind) > 0);
                return totalSize;
            } finally {
                findClose(pFind);
                path.trimTo(len);
            }
        }
        return 0L;
    }

    public static long getDiskFreeSpace(LPSZ path) {
        if (path != null) {
            return getDiskSize(path.ptr());
        }
        // current directory
        return 0L;
    }

    /**
     * Returns fs.file-max kernel limit on Linux or 0 on other OSes.
     */
    public native static long getFileLimit();

    /**
     * Detects if filesystem is supported by QuestDB. The function returns both FS magic and name. Both
     * can be presented to user even if file system is not supported.
     *
     * @param lpszName existing path on the file system. The name of the filesystem is written to this
     *                 address, therefore name should have at least 128 byte capacity
     * @return 0 when OS call failed, errno should be checked. Negative number is file system magic that is supported
     * positive number is magic that is not supported.
     */
    public static int getFileSystemStatus(Path lpszName) {
        assert lpszName.capacity() > 127;
        int status = getFileSystemStatus(lpszName.ptr());
        if (status == VIRTIO_FS_MAGIC) {
            VIRTIO_FS_DETECTED = true;
        }
        return status;
    }

    public static long getLastModified(LPSZ lpsz) {
        return getLastModified(lpsz.ptr());
    }

    /**
     * Returns vm.max_map_count kernel limit on Linux or 0 on other OSes.
     */
    public native static long getMapCountLimit();

    public static String getOpenFdDebugInfo() {
        if (openFds != null) {
            return openFds.toString();
        }
        return null;
    }

    public static long getOpenFileCount() {
        return OPEN_FILE_COUNT.get();
    }

    public @NotNull
    static String getResourcePath(@Nullable URL url) {
        assert url != null;
        String file = url.getFile();
        assert file != null;
        assert !file.isEmpty();
        return file;
    }

    public native static int getStdOutFd();

    public static native int hardLink(long lpszSrc, long lpszHardLink);

    public static int hardLink(LPSZ src, LPSZ hardLink) {
        return hardLink(src.ptr(), hardLink.ptr());
    }

    public static boolean isDirOrSoftLinkDir(LPSZ path) {
        long ptr = findFirst(path);
        if (ptr < 1L) {
            return false;
        }
        try {
            int type = findType(ptr);
            return type == DT_DIR || (type == DT_LNK && isDir(path.ptr()));
        } finally {
            findClose(ptr);
        }
    }

    public static boolean isDirOrSoftLinkDirNoDots(Path path, int rootLen, long pUtf8NameZ, int type) {
        return DT_UNKNOWN != typeDirOrSoftLinkDirNoDots(path, rootLen, pUtf8NameZ, type, null);
    }

    public static boolean isDirOrSoftLinkDirNoDots(Path path, int rootLen, long pUtf8NameZ, int type, @NotNull MutableUtf8Sink nameSink) {
        return DT_UNKNOWN != typeDirOrSoftLinkDirNoDots(path, rootLen, pUtf8NameZ, type, nameSink);
    }

    public static boolean isDots(CharSequence name) {
        return Chars.equals(name, '.') || Chars.equals(name, "..");
    }

    public native static boolean isSoftLink(long lpszPath);

    public static boolean isSoftLink(LPSZ path) {
        return isSoftLink(path.ptr());
    }

    public static long length(LPSZ lpsz) {
        return length0(lpsz.ptr());
    }

    public native static long length(int fd);

    public static native int lock(int fd);

    public static void madvise(long address, long len, int advise) {
        if (Os.type == Os.LINUX_AMD64 || Os.type == Os.LINUX_ARM64) {
            madvise0(address, len, advise);
        }
    }

    public static native void madvise0(long address, long len, int advise);

    public static int mkdir(Path path, int mode) {
        return mkdir(path.ptr(), mode);
    }

    public static int mkdirs(Path path, int mode) {
        for (int i = 0, n = path.size(); i < n; i++) {
            byte b = path.byteAt(i);
            if (b == Files.SEPARATOR) {
                // do not attempt to create '/' on linux or 'C:\' on Windows
                if ((i == 0 && Os.isPosix()) || (i == 2 && Os.isWindows() && path.byteAt(1) == ':')) {
                    continue;
                }

                // replace separator we just found with \0
                // temporarily truncate path to the directory we need to create
                path.$at(i);
                if (path.size() > 0 && !Files.exists(path)) {
                    int r = Files.mkdir(path, mode);
                    if (r != 0) {
                        path.put(i, (byte) Files.SEPARATOR);
                        return r;
                    }
                }
                path.put(i, (byte) Files.SEPARATOR);
            }
        }
        return 0;
    }

    public static long mmap(int fd, long len, long offset, int flags, int memoryTag) {
        long address = mmap0(fd, len, offset, flags, 0);
        if (address != -1) {
            Unsafe.recordMemAlloc(len, memoryTag);
        }
        return address;
    }

    public static long mremap(int fd, long address, long previousSize, long newSize, long offset, int flags, int memoryTag) {
        if (newSize < 1) {
            throw CairoException.critical(0).put("could not remap file, invalid newSize [previousSize=").put(previousSize)
                    .put(", newSize=").put(newSize)
                    .put(", offset=").put(offset)
                    .put(", fd=").put(fd)
                    .put(']');
        }

        address = mremap0(fd, address, previousSize, newSize, offset, flags);
        if (address != -1) {
            Unsafe.recordMemAlloc(newSize - previousSize, memoryTag);
        }
        return address;
    }

    public static native int msync(long addr, long len, boolean async);

    public static void munmap(long address, long len, int memoryTag) {
        if (address != 0 && munmap0(address, len) != -1) {
            Unsafe.recordMemAlloc(-len, memoryTag);
        }
    }

    public static native long noop();

    public static boolean notDots(Utf8Sequence value) {
        final int size = value.size();
        if (size > 2) {
            return true;
        }
        if (value.byteAt(0) != '.') {
            return true;
        }
        return size == 2 && value.byteAt(1) != '.';
    }

    public static boolean notDots(long pUtf8NameZ) {
        final byte b0 = Unsafe.getUnsafe().getByte(pUtf8NameZ);

        if (b0 != '.') {
            return true;
        }

        final byte b1 = Unsafe.getUnsafe().getByte(pUtf8NameZ + 1);
        return b1 != 0 && (b1 != '.' || Unsafe.getUnsafe().getByte(pUtf8NameZ + 2) != 0);
    }

    public static int openAppend(LPSZ lpsz) {
        return bumpFileCount(openAppend(lpsz.ptr()));
    }

    public static int openCleanRW(LPSZ lpsz, long size) {
        return bumpFileCount(openCleanRW(lpsz.ptr(), size));
    }

    public native static int openCleanRW(long lpszName, long size);

    public static int openRO(LPSZ lpsz) {
        return bumpFileCount(openRO(lpsz.ptr()));
    }

    public static int openRW(LPSZ lpsz) {
        return bumpFileCount(openRW(lpsz.ptr()));
    }

    public static int openRW(LPSZ lpsz, long opts) {
        return bumpFileCount(openRWOpts(lpsz.ptr(), opts));
    }

    public native static long read(int fd, long address, long len, long offset);

    public static boolean readLink(Path softLink, Path readTo) {
        final int len = readTo.size();
        final int bufSize = 1024;
        readTo.zeroPad(bufSize);
        // readlink copies link target into the give buffer, without null-terminating it
        // the buffer therefor is filled with zeroes. It is also possible that buffer is
        // not large enough to copy the entire target. We detect this by checking the return
        // value. If the value is the same as the buffer size we make an assumption that
        // the link target is perhaps longer than the buffer.

        int res = readLink0(softLink.ptr(), readTo.ptr() + len, bufSize);
        if (res > 0 && res < bufSize) {
            readTo.trimTo(len + res).$();
            // check if symlink is absolute or relative
            if (readTo.byteAt(0) != '/') {
                int prefixLen = Utf8s.lastIndexOfAscii(softLink, '/');
                if (prefixLen > 0) {
                    readTo.prefix(softLink, prefixLen + 1).$();
                }
            }
            return true;
        }
        return false;
    }

    public native static byte readNonNegativeByte(int fd, long offset);

    public native static int readNonNegativeInt(int fd, long offset);

    public native static long readNonNegativeLong(int fd, long offset);

    public native static short readNonNegativeShort(int fd, long offset);

    public static boolean remove(LPSZ lpsz) {
        return remove(lpsz.ptr());
    }

    public static int rename(LPSZ oldName, LPSZ newName) {
        return rename(oldName.ptr(), newName.ptr());
    }

    /**
     * Removes directory recursively. When function fails the caller has to check Os.errno() for the diagnostics.
     * The function can operate in two modes, eager and haltOnFail. In haltOnFail mode function fails fast, providing precise
     * error number. In eager mode function will free most of the disk space but likely to fail on deleting non-empty
     * directory, should some files remain. Thus, not providing correct diagnostics.
     * <p>
     * rmdir() will fail if directory does not exist
     *
     * @param path       path to the directory, must include trailing slash (/)
     * @param haltOnFail when true removing directory will halt on first failed attempt to remove directory contents. When
     *                   false, the function will remove as many files and subdirectories as possible. That might be useful
     *                   when the intent is too free up as much disk space as possible.
     * @return true on success
     */
    public static boolean rmdir(Path path, boolean haltOnFail) {
        long pathUtf8Ptr = path.ptr();
        long pFind = findFirst(pathUtf8Ptr);
        if (pFind > 0L) {
            int len = path.size();
            boolean res;
            int type;
            long nameUtf8Ptr;
            try {
                do {
                    nameUtf8Ptr = findName(pFind);
                    path.trimTo(len).concat(nameUtf8Ptr).$();
                    type = findType(pFind);
                    if (type == Files.DT_FILE) {
                        if (!remove(pathUtf8Ptr) && haltOnFail) {
                            return false;
                        }
                    } else if (notDots(nameUtf8Ptr)) {
                        res = type == Files.DT_LNK ? unlink(pathUtf8Ptr) == 0 : rmdir(path, haltOnFail);
                        if (!res && haltOnFail) {
                            return false;
                        }
                    }
                }
                while (findNext(pFind) > 0);
            } finally {
                findClose(pFind);
                path.trimTo(len).$();
            }

            if (isSoftLink(pathUtf8Ptr)) {
                return unlink(pathUtf8Ptr) == 0;
            }
            return rmdir(pathUtf8Ptr);
        }
        return false;
    }

    public static boolean setLastModified(LPSZ lpsz, long millis) {
        return setLastModified(lpsz.ptr(), millis);
    }

    public static native int softLink(long lpszSrc, long lpszSoftLink);

    public static int softLink(LPSZ src, LPSZ softLink) {
        return softLink(src.ptr(), softLink.ptr());
    }

    public static native int sync();

    public static boolean touch(LPSZ lpsz) {
        int fd = openRW(lpsz);
        boolean result = fd > 0;
        if (result) {
            close(fd);
        }
        return result;
    }

    public native static boolean truncate(int fd, long size);

    public static int typeDirOrSoftLinkDirNoDots(Path path, int rootLen, long pUtf8NameZ, int type, @Nullable MutableUtf8Sink nameSink) {
        if (!notDots(pUtf8NameZ)) {
            return DT_UNKNOWN;
        }

        if (type == DT_DIR) {
            if (nameSink != null) {
                nameSink.clear();
                Utf8s.utf8ZCopy(pUtf8NameZ, nameSink);
            }
            path.trimTo(rootLen).concat(pUtf8NameZ).$();
            return DT_DIR;
        }

        if (type == DT_LNK) {
            if (nameSink != null) {
                nameSink.clear();
                Utf8s.utf8ZCopy(pUtf8NameZ, nameSink);
            }
            path.trimTo(rootLen).concat(pUtf8NameZ).$();
            if (isDir(path.ptr())) {
                return DT_LNK;
            }
        }

        return DT_UNKNOWN;
    }

    public native static int unlink(long lpszSoftLink);

    public static int unlink(LPSZ softLink) {
        return unlink(softLink.ptr());
    }

    public static void walk(Path path, FindVisitor func) {
        int len = path.size();
        long p = findFirst(path);
        if (p > 0) {
            try {
                do {
                    long name = findName(p);
                    if (notDots(name)) {
                        int type = findType(p);
                        path.trimTo(len);
                        if (type == Files.DT_FILE) {
                            func.onFind(name, type);
                        } else {
                            walk(path.concat(name).$(), func);
                        }
                    }
                } while (findNext(p) > 0);
            } finally {
                findClose(p);
            }
        }
    }

    public native static long write(int fd, long address, long len, long offset);

    private native static int close0(int fd);

    private static native boolean exists0(long lpsz);

    // caller must call findClose to free allocated struct
    private native static long findFirst(long lpszName);

    private static native long getDiskSize(long lpszPath);

    private static native int getFileSystemStatus(long lpszName);

    private native static long getLastModified(long lpszName);

    private native static long getPageSize();

    private native static int getPosixFadvRandom();

    private native static int getPosixFadvSequential();

    private native static int getPosixMadvRandom();

    private native static int getPosixMadvSequential();

    private native static boolean isDir(long pUtf8PathZ);

    private native static long length0(long lpszName);

    private native static int mkdir(long lpszPath, int mode);

    private static native long mmap0(int fd, long len, long offset, int flags, long baseAddress);

    private static native long mremap0(int fd, long address, long previousSize, long newSize, long offset, int flags);

    private static native int munmap0(long address, long len);

    private native static int openAppend(long lpszName);

    private native static int openRO(long lpszName);

    private native static int openRW(long lpszName);

    private native static int openRWOpts(long lpszName, long opts);

    private static native int readLink0(long lpszPath, long buffer, int len);

    private native static boolean remove(long lpsz);

    private static native int rename(long lpszOld, long lpszNew);

    private native static boolean rmdir(long lpsz);

    private native static boolean setLastModified(long lpszName, long millis);

    static {
        Os.init();
        UTF_8 = StandardCharsets.UTF_8;
        PAGE_SIZE = getPageSize();
        SEPARATOR = File.separatorChar;
        if (Os.type == Os.LINUX_AMD64 || Os.type == Os.LINUX_ARM64) {
            POSIX_FADV_RANDOM = getPosixFadvRandom();
            POSIX_FADV_SEQUENTIAL = getPosixFadvSequential();
            POSIX_MADV_RANDOM = getPosixMadvRandom();
            POSIX_MADV_SEQUENTIAL = getPosixMadvSequential();
        } else {
            POSIX_FADV_SEQUENTIAL = -1;
            POSIX_FADV_RANDOM = -1;
            POSIX_MADV_SEQUENTIAL = -1;
            POSIX_MADV_RANDOM = -1;
        }
    }
}
