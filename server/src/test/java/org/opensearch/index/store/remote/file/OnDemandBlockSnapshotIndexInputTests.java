/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.store.remote.file;

import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.LockFactory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.SimpleFSLockFactory;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.Version;
import org.junit.After;
import org.junit.Before;
import org.opensearch.common.unit.ByteSizeValue;
import org.opensearch.index.snapshots.blobstore.BlobStoreIndexShardSnapshot;
import org.opensearch.index.store.StoreFileMetadata;
import org.opensearch.index.store.remote.utils.BlobFetchRequest;
import org.opensearch.index.store.remote.utils.TransferManager;
import org.opensearch.test.OpenSearchTestCase;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

public class OnDemandBlockSnapshotIndexInputTests extends OpenSearchTestCase {
    // params shared by all test cases
    private static final String RESOURCE_DESCRIPTION = "Test OnDemandBlockSnapshotIndexInput Block Size";
    private static final long BLOCK_SNAPSHOT_FILE_OFFSET = 0;
    private static final String FILE_NAME = "File_Name";
    private static final String BLOCK_FILE_PREFIX = FILE_NAME;
    private static final boolean IS_CLONE = false;
    private static final ByteSizeValue BYTE_SIZE_VALUE = new ByteSizeValue(1L);
    private TransferManager transferManager;
    private LockFactory lockFactory;
    private BlobStoreIndexShardSnapshot.FileInfo fileInfo;
    private Path path;

    @Before
    public void init() {
        transferManager = mock(TransferManager.class);
        lockFactory = SimpleFSLockFactory.INSTANCE;
        path = LuceneTestCase.createTempDir("OnDemandBlockSnapshotIndexInputTests");
    }

    @After
    public void clean() {
        try {
            cleanDirectory(path);
        } catch (Exception e) {
            fail();
        }
    }

    public void testVariousBlockSize() throws Exception {
        int fileSize = 29360128;
        int blockSizeShift;

        // block size 8MB, default one
        blockSizeShift = 23;
        OnDemandBlockSnapshotIndexInput ondemandBlockSnapshotIndexInput_8MB = createOnDemandBlockSnapshotIndexInput(
            blockSizeShift,
            fileSize
        );
        runAllTestsFor(ondemandBlockSnapshotIndexInput_8MB, 1 << blockSizeShift, fileSize);
        cleanDirectory(path);

        // block size 4KB
        blockSizeShift = 12;
        OnDemandBlockSnapshotIndexInput ondemandBlockSnapshotIndexInput_4KB = createOnDemandBlockSnapshotIndexInput(
            blockSizeShift,
            fileSize
        );
        runAllTestsFor(ondemandBlockSnapshotIndexInput_4KB, 1 << blockSizeShift, fileSize);
        cleanDirectory(path);

        // block size 1MB
        blockSizeShift = 20;
        OnDemandBlockSnapshotIndexInput ondemandBlockSnapshotIndexInput_1MB = createOnDemandBlockSnapshotIndexInput(
            blockSizeShift,
            fileSize
        );
        runAllTestsFor(ondemandBlockSnapshotIndexInput_1MB, 1 << blockSizeShift, fileSize);
        cleanDirectory(path);

        // block size 4MB
        blockSizeShift = 22;
        OnDemandBlockSnapshotIndexInput ondemandBlockSnapshotIndexInput_4MB = createOnDemandBlockSnapshotIndexInput(
            blockSizeShift,
            fileSize
        );
        runAllTestsFor(ondemandBlockSnapshotIndexInput_4MB, 1 << blockSizeShift, fileSize);
        cleanDirectory(path);
    }

    public void runAllTestsFor(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize, int fileSize) throws Exception {
        TestGroup.testGetBlock(blockedSnapshotFile, blockSize, fileSize);
        TestGroup.testGetBlockOffset(blockedSnapshotFile, blockSize, fileSize);
        TestGroup.testGetBlockStart(blockedSnapshotFile, blockSize);
        TestGroup.testCurrentBlockStart(blockedSnapshotFile, blockSize);
        TestGroup.testCurrentBlockPosition(blockedSnapshotFile, blockSize);
        TestGroup.testClone(blockedSnapshotFile, blockSize);
        TestGroup.testSlice(blockedSnapshotFile, blockSize);
        TestGroup.testGetFilePointer(blockedSnapshotFile, blockSize);
        TestGroup.testReadByte(blockedSnapshotFile, blockSize);
        TestGroup.testReadShort(blockedSnapshotFile, blockSize);
        TestGroup.testReadInt(blockedSnapshotFile, blockSize);
        TestGroup.testReadLong(blockedSnapshotFile, blockSize);
        TestGroup.testReadVInt(blockedSnapshotFile, blockSize);
        TestGroup.testSeek(blockedSnapshotFile, blockSize, fileSize);
        TestGroup.testReadByteWithPos(blockedSnapshotFile, blockSize);
        TestGroup.testReadShortWithPos(blockedSnapshotFile, blockSize);
        TestGroup.testReadIntWithPos(blockedSnapshotFile, blockSize);
        TestGroup.testReadLongWithPos(blockedSnapshotFile, blockSize);
        TestGroup.testReadBytes(blockedSnapshotFile, blockSize);
    }

    // create OnDemandBlockSnapshotIndexInput for each block size
    private OnDemandBlockSnapshotIndexInput createOnDemandBlockSnapshotIndexInput(int blockSizeShift, long fileSize) {

        // file info should be initialized per test method since file size need to be calculated
        fileInfo = new BlobStoreIndexShardSnapshot.FileInfo(
            FILE_NAME,
            new StoreFileMetadata(FILE_NAME, fileSize, "", Version.LATEST),
            BYTE_SIZE_VALUE
        );

        int blockSize = 1 << blockSizeShift;

        doAnswer(invocation -> {
            BlobFetchRequest blobFetchRequest = invocation.getArgument(0);
            return CompletableFuture.completedFuture(
                blobFetchRequest.getDirectory().openInput(blobFetchRequest.getFileName(), IOContext.READ)
            );
        }).when(transferManager).asyncFetchBlob(any());

        FSDirectory directory = null;
        try {
            cleanDirectory(path);
            // use MMapDirectory to create block
            directory = new MMapDirectory(path, lockFactory);
        } catch (IOException e) {
            fail("fail to create MMapDirectory: " + e.getMessage());
        }

        initBlockFiles(blockSize, fileSize, directory);

        return new OnDemandBlockSnapshotIndexInput(
            OnDemandBlockIndexInput.builder()
                .resourceDescription(RESOURCE_DESCRIPTION)
                .offset(BLOCK_SNAPSHOT_FILE_OFFSET)
                .length(fileSize)
                .blockSizeShift(blockSizeShift)
                .isClone(IS_CLONE),
            fileInfo,
            directory,
            transferManager
        );
    }

    private void initBlockFiles(int blockSize, long fileSize, FSDirectory fsDirectory) {
        // for this test, it's safe to convert long to int
        int intFileSize = (int) fileSize;
        int numOfBlocks = intFileSize / blockSize;

        int sizeOfLastBlock = intFileSize % blockSize;

        try {

            // block size will always be an integer multiple of frame size
            // write 48, -80 alternatively
            for (int i = 0; i < numOfBlocks; i++) {
                // create normal blocks
                String blockName = BLOCK_FILE_PREFIX + "." + i;
                IndexOutput output = fsDirectory.createOutput(blockName, null);
                // since block size is always even number, safe to do division
                for (int j = 0; j < blockSize / 2; j++) {
                    // byte 00110000
                    output.writeByte((byte) 48);
                    // byte 10110000
                    output.writeByte((byte) -80);
                }
                output.close();
            }

            if (numOfBlocks > 1 && sizeOfLastBlock != 0) {
                // create last block
                String lastBlockName = BLOCK_FILE_PREFIX + "." + numOfBlocks;
                IndexOutput output = fsDirectory.createOutput(lastBlockName, null);
                for (int i = 0; i < sizeOfLastBlock; i++) {
                    if ((i & 1) == 0) {
                        output.writeByte((byte) 48);
                    } else {
                        output.writeByte((byte) -80);
                    }
                }
                output.close();
            }

        } catch (IOException e) {
            fail("fail to initialize block files: " + e.getMessage());
        }

    }

    private void cleanDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path).sorted(Comparator.reverseOrder()).forEach(f -> {
                try {
                    Files.delete(f);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    public static class TestGroup {

        public static void testGetBlock(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize, int fileSize) {
            // block 0
            assertEquals(0, blockedSnapshotFile.getBlock(0L));

            // block 1
            assertEquals(1, blockedSnapshotFile.getBlock(blockSize));

            // end block
            assertEquals((fileSize - 1) / blockSize, blockedSnapshotFile.getBlock(fileSize - 1));
        }

        public static void testGetBlockOffset(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize, int fileSize) {
            // block 0
            assertEquals(1, blockedSnapshotFile.getBlockOffset(1));

            // block 1
            assertEquals(0, blockedSnapshotFile.getBlockOffset(blockSize));

            // end block
            assertEquals((fileSize - 1) % blockSize, blockedSnapshotFile.getBlockOffset(fileSize - 1));
        }

        public static void testGetBlockStart(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) {
            // block 0
            assertEquals(0L, blockedSnapshotFile.getBlockStart(0));

            // block 1
            assertEquals(blockSize, blockedSnapshotFile.getBlockStart(1));

            // block 2
            assertEquals(blockSize * 2, blockedSnapshotFile.getBlockStart(2));
        }

        public static void testCurrentBlockStart(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            // block 0
            blockedSnapshotFile.seek(blockSize - 1);
            assertEquals(0L, blockedSnapshotFile.currentBlockStart());

            // block 1
            blockedSnapshotFile.seek(blockSize * 2 - 1);
            assertEquals(blockSize, blockedSnapshotFile.currentBlockStart());

            // block 2
            blockedSnapshotFile.seek(blockSize * 3 - 1);
            assertEquals(blockSize * 2, blockedSnapshotFile.currentBlockStart());
        }

        public static void testCurrentBlockPosition(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            // block 0
            blockedSnapshotFile.seek(blockSize - 1);
            assertEquals(blockSize - 1, blockedSnapshotFile.currentBlockPosition());

            // block 1
            blockedSnapshotFile.seek(blockSize + 1);
            assertEquals(1, blockedSnapshotFile.currentBlockPosition());

            // block 2
            blockedSnapshotFile.seek(blockSize * 2 + 11);
            assertEquals(11, blockedSnapshotFile.currentBlockPosition());
        }

        public static void testClone(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            blockedSnapshotFile.seek(blockSize + 1);
            OnDemandBlockSnapshotIndexInput clonedFile = blockedSnapshotFile.clone();
            assertEquals(clonedFile.currentBlock.getFilePointer(), blockedSnapshotFile.currentBlock.getFilePointer());
            assertEquals(clonedFile.getFilePointer(), blockedSnapshotFile.getFilePointer());
            clonedFile.seek(blockSize + 11);
            assertNotEquals(clonedFile.currentBlock.getFilePointer(), blockedSnapshotFile.currentBlock.getFilePointer());
        }

        public static void testSlice(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            IndexInput slice = blockedSnapshotFile.slice("slice", blockSize - 11, 22);
            OnDemandBlockSnapshotIndexInput newSlice = (OnDemandBlockSnapshotIndexInput) slice;

            assertEquals(newSlice.isClone, true);
            assertEquals(newSlice.transferManager, blockedSnapshotFile.transferManager);
            assertEquals(newSlice.fileName, blockedSnapshotFile.fileName);
            assertEquals(newSlice.blockMask, blockedSnapshotFile.blockMask);
            assertEquals(newSlice.blockSize, blockedSnapshotFile.blockSize);
            assertEquals(newSlice.blockSizeShift, blockedSnapshotFile.blockSizeShift);
            assertEquals(newSlice.directory, blockedSnapshotFile.directory);
            assertNotEquals(newSlice.length, blockedSnapshotFile.length);
            assertNotEquals(newSlice.offset, blockedSnapshotFile.offset);

            newSlice.seek(0);
            assertEquals(0, newSlice.getFilePointer());
            assertEquals(0, newSlice.currentBlockId);
            assertEquals(blockSize - 11, newSlice.currentBlock.getFilePointer());
            newSlice.seek(21);
            assertEquals(21, newSlice.getFilePointer());
            assertEquals(1, newSlice.currentBlockId);
            assertEquals(10, newSlice.currentBlock.getFilePointer());

            try {
                newSlice.seek(23);
            } catch (EOFException e) {
                return;
            }
            fail("Able to seek past file end");
        }

        public static void testGetFilePointer(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            blockedSnapshotFile.seek(blockSize - 11);
            assertEquals(blockSize - 11, blockedSnapshotFile.currentBlock.getFilePointer());
            blockedSnapshotFile.seek(blockSize + 5);
            assertEquals(5, blockedSnapshotFile.currentBlock.getFilePointer());
            blockedSnapshotFile.seek(blockSize * 2);
            assertEquals(0, blockedSnapshotFile.currentBlock.getFilePointer());
        }

        public static void testReadByte(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            blockedSnapshotFile.seek(0);
            assertEquals((byte) 48, blockedSnapshotFile.readByte());
            blockedSnapshotFile.seek(1);
            assertEquals((byte) -80, blockedSnapshotFile.readByte());

            blockedSnapshotFile.seek(blockSize - 1);
            assertEquals((byte) -80, blockedSnapshotFile.readByte());
            blockedSnapshotFile.seek(blockSize);
            assertEquals((byte) 48, blockedSnapshotFile.readByte());
        }

        public static void testReadShort(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            blockedSnapshotFile.seek(0);
            assertEquals(-20432, blockedSnapshotFile.readShort());

            blockedSnapshotFile.seek(blockSize);
            assertEquals(-20432, blockedSnapshotFile.readShort());

            // cross block 0 and block 1
            blockedSnapshotFile.seek(blockSize - 1);
            assertEquals(12464, blockedSnapshotFile.readShort());
        }

        public static void testReadInt(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            blockedSnapshotFile.seek(0);
            assertEquals(-1338986448, blockedSnapshotFile.readInt());

            blockedSnapshotFile.seek(blockSize);
            assertEquals(-1338986448, blockedSnapshotFile.readInt());

            // 3 byte in block 0, 1 byte in block 1
            blockedSnapshotFile.seek(blockSize - 3);
            assertEquals(816853168, blockedSnapshotFile.readInt());
            // 2 byte in block 0, 2 byte in block 1
            blockedSnapshotFile.seek(blockSize - 2);
            assertEquals(-1338986448, blockedSnapshotFile.readInt());
            // 1 byte in block 0, 3 byte in block 1
            blockedSnapshotFile.seek(blockSize - 1);
            assertEquals(816853168, blockedSnapshotFile.readInt());
        }

        public static void testReadLong(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            blockedSnapshotFile.seek(0);
            assertEquals(-5750903000991223760L, blockedSnapshotFile.readLong());

            // 7 byte in block 0, 1 byte in block 1
            blockedSnapshotFile.seek(blockSize - 7);
            assertEquals(3508357643010846896L, blockedSnapshotFile.readLong());

            // 6 byte in block 0, 2 byte in block 2
            blockedSnapshotFile.seek(blockSize - 6);
            assertEquals(-5750903000991223760L, blockedSnapshotFile.readLong());

            // 5 byte in block 0, 3 byte in block 3
            blockedSnapshotFile.seek(blockSize - 5);
            assertEquals(3508357643010846896L, blockedSnapshotFile.readLong());

            // 4 byte in block 0, 4 block in block 4
            blockedSnapshotFile.seek(blockSize - 4);
            assertEquals(-5750903000991223760L, blockedSnapshotFile.readLong());
        }

        public static void testReadVInt(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            blockedSnapshotFile.seek(0);
            assertEquals(48, blockedSnapshotFile.readVInt());

            blockedSnapshotFile.seek(blockSize - 1);
            assertEquals(6192, blockedSnapshotFile.readVInt());
        }

        public static void testReadVLong(OnDemandBlockSnapshotIndexInput blockedSnapshotFile) throws IOException {
            blockedSnapshotFile.seek(0);
            assertEquals(48, blockedSnapshotFile.readVLong());

            blockedSnapshotFile.seek(1);
            assertEquals(6192, blockedSnapshotFile.readVLong());
        }

        public static void testSeek(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize, int fileSize) throws IOException {
            blockedSnapshotFile.seek(0);
            assertEquals(0, blockedSnapshotFile.currentBlock.getFilePointer());

            blockedSnapshotFile.seek(blockSize + 11);
            assertEquals(11, blockedSnapshotFile.currentBlock.getFilePointer());

            try {
                blockedSnapshotFile.seek(fileSize + 1);
            } catch (EOFException e) {
                return;
            }
            fail("Able to seek past end");
        }

        public static void testReadByteWithPos(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            assertEquals(48, blockedSnapshotFile.readByte(0));
            assertEquals(-80, blockedSnapshotFile.readByte(1));

            assertEquals(48, blockedSnapshotFile.readByte(blockSize));
            assertEquals(-80, blockedSnapshotFile.readByte(blockSize + 1));
        }

        public static void testReadShortWithPos(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            assertEquals(-20432, blockedSnapshotFile.readShort(0));
            assertEquals(12464, blockedSnapshotFile.readShort(1));

            assertEquals(12464, blockedSnapshotFile.readShort(blockSize - 1));
            assertEquals(-20432, blockedSnapshotFile.readShort(blockSize));
        }

        public static void testReadIntWithPos(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            assertEquals(-1338986448, blockedSnapshotFile.readInt(0));
            assertEquals(-1338986448, blockedSnapshotFile.readInt(blockSize));

            // 3 byte in block 0, 1 byte in block 1
            assertEquals(816853168, blockedSnapshotFile.readInt(blockSize - 3));
            // 2 byte in block 0, 2 byte in block 1
            assertEquals(-1338986448, blockedSnapshotFile.readInt(blockSize - 2));
            // 1 byte in block 0, 3 byte in block 1
            assertEquals(816853168, blockedSnapshotFile.readInt(blockSize - 1));
        }

        public static void testReadLongWithPos(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            assertEquals(-5750903000991223760L, blockedSnapshotFile.readLong(0));

            // 7 byte in block 0, 1 byte in block 1
            assertEquals(3508357643010846896L, blockedSnapshotFile.readLong(blockSize - 7));

            // 6 byte in block 0, 2 byte in block 2
            assertEquals(-5750903000991223760L, blockedSnapshotFile.readLong(blockSize - 6));

            // 5 byte in block 0, 3 byte in block 3
            assertEquals(3508357643010846896L, blockedSnapshotFile.readLong(blockSize - 5));

            // 4 byte in block 0, 4 block in block 4
            assertEquals(-5750903000991223760L, blockedSnapshotFile.readLong(blockSize - 4));
        }

        public static void testReadBytes(OnDemandBlockSnapshotIndexInput blockedSnapshotFile, int blockSize) throws IOException {
            byte[] byteArr = new byte[2];
            blockedSnapshotFile.seek(0);
            blockedSnapshotFile.readBytes(byteArr, 0, 2);
            assertEquals(48, byteArr[0]);
            assertEquals(-80, byteArr[1]);

            blockedSnapshotFile.seek(blockSize - 1);
            blockedSnapshotFile.readBytes(byteArr, 0, 2);
            assertEquals(-80, byteArr[0]);
            assertEquals(48, byteArr[1]);
        }
    }
}
