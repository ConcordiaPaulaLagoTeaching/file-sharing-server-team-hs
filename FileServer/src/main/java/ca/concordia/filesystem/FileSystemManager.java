package ca.concordia.filesystem;

import ca.concordia.filesystem.datastructures.FEntry;
import ca.concordia.filesystem.datastructures.FNode;

import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class FileSystemManager {

    private final int MAXFILES;
    private final int MAXBLOCKS;
    private final RandomAccessFile disk;
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    
    private static final int BLOCK_SIZE = 128;
    private static final int FENTRY_SIZE = 15; // 11 bytes filename + 2 bytes size + 2 bytes firstBlock
    private static final int FNODE_SIZE = 8; // 4 bytes blockIndex + 4 bytes next
    
    private FEntry[] fentryTable;
    private FNode[] fnodeTable;
    private boolean[] freeBlockList;
    private long metadataSize; // Size of metadata in bytes
    private long dataStartOffset; // Offset where data blocks start
    
    // Reflection fields for accessing private members
    private static Field fnodeBlockIndexField;
    private static Field fnodeNextField;
    private static Field fentryFirstBlockField;
    
    static {
        try {
            fnodeBlockIndexField = FNode.class.getDeclaredField("blockIndex");
            fnodeBlockIndexField.setAccessible(true);
            fnodeNextField = FNode.class.getDeclaredField("next");
            fnodeNextField.setAccessible(true);
            fentryFirstBlockField = FEntry.class.getDeclaredField("firstBlock");
            fentryFirstBlockField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize reflection fields", e);
        }
    }
    
    // Helper methods to access FNode fields via reflection
    private int getFNodeBlockIndex(FNode node) {
        try {
            return fnodeBlockIndexField.getInt(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    private void setFNodeBlockIndex(FNode node, int value) {
        try {
            fnodeBlockIndexField.setInt(node, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    private int getFNodeNext(FNode node) {
        try {
            return fnodeNextField.getInt(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    private void setFNodeNext(FNode node, int value) {
        try {
            fnodeNextField.setInt(node, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    // Helper methods to access FEntry fields via reflection
    private void setFEntryFirstBlock(FEntry entry, short value) {
        try {
            fentryFirstBlockField.setShort(entry, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    public FileSystemManager(String filename, int totalSize) throws Exception {
        this.MAXFILES = 5;
        this.MAXBLOCKS = 10;
        
        // Calculate metadata size
        long fentryArraySize = (long) MAXFILES * FENTRY_SIZE;
        long fnodeArraySize = (long) MAXBLOCKS * FNODE_SIZE;
        this.metadataSize = fentryArraySize + fnodeArraySize;
        
        // Calculate how many blocks metadata takes
        long metadataBlocks = (metadataSize + BLOCK_SIZE - 1) / BLOCK_SIZE; // Ceiling division
        this.dataStartOffset = metadataBlocks * BLOCK_SIZE;
        
        // Initialize disk file
        this.disk = new RandomAccessFile(filename, "rw");
        
        // Initialize in-memory structures
        this.fentryTable = new FEntry[MAXFILES];
        this.fnodeTable = new FNode[MAXBLOCKS];
        this.freeBlockList = new boolean[MAXBLOCKS];
        
        // Initialize or load file system
        if (disk.length() == 0) {
            // New file system - initialize
            initializeFileSystem();
        } else {
            // Existing file system - load from disk
            loadFileSystem();
        }
    }
    
    private void initializeFileSystem() throws Exception {
        // Initialize all FEntries as empty
        for (int i = 0; i < MAXFILES; i++) {
            fentryTable[i] = new FEntry("", (short) 0, (short) -1);
        }
        
        // Initialize all FNodes
        // According to spec: blockIndex magnitude = FNode index, negative if unused
        for (int i = 0; i < MAXBLOCKS; i++) {
            fnodeTable[i] = new FNode(-i); // Negative indicates unused, magnitude = index
            setFNodeNext(fnodeTable[i], -1);
        }
        
        // Mark all blocks as free initially
        for (int i = 0; i < MAXBLOCKS; i++) {
            freeBlockList[i] = true;
        }
        
        // Write metadata to disk
        writeMetadata();
    }
    
    private void loadFileSystem() throws Exception {
        disk.seek(0);
        
        // Read FEntry array
        for (int i = 0; i < MAXFILES; i++) {
            byte[] filenameBytes = new byte[11];
            disk.readFully(filenameBytes);
            String filename = new String(filenameBytes, StandardCharsets.UTF_8).trim();
            
            short filesize = disk.readShort();
            short firstBlock = disk.readShort();
            
            fentryTable[i] = new FEntry(filename, filesize, firstBlock);
        }
        
        // Read FNode array
        for (int i = 0; i < MAXBLOCKS; i++) {
            int blockIndex = disk.readInt();
            int next = disk.readInt();
            
            fnodeTable[i] = new FNode(blockIndex);
            setFNodeNext(fnodeTable[i], next);
        }
        
        // Reconstruct free block list
        for (int i = 0; i < MAXBLOCKS; i++) {
            freeBlockList[i] = (getFNodeBlockIndex(fnodeTable[i]) < 0);
        }
    }
    
    private void writeMetadata() throws Exception {
        disk.seek(0);
        
        // Write FEntry array
        for (int i = 0; i < MAXFILES; i++) {
            FEntry entry = fentryTable[i];
            byte[] filenameBytes = new byte[11];
            byte[] nameBytes = entry.getFilename().getBytes(StandardCharsets.UTF_8);
            System.arraycopy(nameBytes, 0, filenameBytes, 0, Math.min(nameBytes.length, 11));
            disk.write(filenameBytes);
            disk.writeShort(entry.getFilesize());
            disk.writeShort(entry.getFirstBlock());
        }
        
        // Write FNode array
        for (int i = 0; i < MAXBLOCKS; i++) {
            FNode node = fnodeTable[i];
            disk.writeInt(getFNodeBlockIndex(node));
            disk.writeInt(getFNodeNext(node));
        }
        
        disk.getFD().sync(); // Force write to disk
    }
    
    public void createFile(String fileName) throws Exception {
        if (fileName.length() > 11) {
            throw new Exception("ERROR: filename too large");
        }
        
        readWriteLock.writeLock().lock();
        try {
            // Check if file already exists
            for (int i = 0; i < MAXFILES; i++) {
                if (fentryTable[i] != null && fentryTable[i].getFilename().equals(fileName)) {
                    throw new Exception("ERROR: file " + fileName + " already exists");
                }
            }
            
            // Find free FEntry slot
            int freeEntryIndex = -1;
            for (int i = 0; i < MAXFILES; i++) {
                if (fentryTable[i] == null || fentryTable[i].getFilename().isEmpty()) {
                    freeEntryIndex = i;
                    break;
                }
            }
            
            if (freeEntryIndex == -1) {
                throw new Exception("ERROR: no free file entries available");
            }
            
            // Create new file entry
            fentryTable[freeEntryIndex] = new FEntry(fileName, (short) 0, (short) -1);
            
            // Write metadata to disk
            writeMetadata();
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }
    
    public void deleteFile(String fileName) throws Exception {
        readWriteLock.writeLock().lock();
        try {
            // Find file entry
            int entryIndex = -1;
            for (int i = 0; i < MAXFILES; i++) {
                if (fentryTable[i] != null && fentryTable[i].getFilename().equals(fileName)) {
                    entryIndex = i;
                    break;
                }
            }
            
            if (entryIndex == -1) {
                throw new Exception("ERROR: file " + fileName + " does not exist");
            }
            
            FEntry entry = fentryTable[entryIndex];
            short firstBlockIndex = entry.getFirstBlock();
            
            // Free all blocks used by this file
            int currentFNodeIndex = firstBlockIndex;
            while (currentFNodeIndex != -1 && currentFNodeIndex >= 0 && currentFNodeIndex < MAXBLOCKS) {
                FNode currentNode = fnodeTable[currentFNodeIndex];
                int dataBlockIndex = getFNodeBlockIndex(currentNode);
                
                // Overwrite data block with zeros
                if (dataBlockIndex >= 0) {
                    long blockOffset = dataStartOffset + (long) dataBlockIndex * BLOCK_SIZE;
                    disk.seek(blockOffset);
                    byte[] zeros = new byte[BLOCK_SIZE];
                    disk.write(zeros);
                }
                
                // Mark FNode as free
                int nextFNodeIndex = getFNodeNext(currentNode);
                setFNodeBlockIndex(currentNode, -currentFNodeIndex); // Negative, magnitude = FNode index
                setFNodeNext(currentNode, -1);
                freeBlockList[currentFNodeIndex] = true;
                
                currentFNodeIndex = nextFNodeIndex;
            }
            
            // Clear file entry
            fentryTable[entryIndex] = new FEntry("", (short) 0, (short) -1);
            
            // Write metadata to disk
            writeMetadata();
            disk.getFD().sync();
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }
    