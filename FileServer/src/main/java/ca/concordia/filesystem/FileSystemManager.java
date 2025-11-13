package ca.concordia.filesystem;

import ca.concordia.filesystem.datastructures.FEntry;

import java.io.RandomAccessFile;
import java.util.concurrent.locks.ReentrantLock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class FileSystemManager {

    private final int MAXFILES = 5;
    private final int MAXBLOCKS = 10;
    private final static FileSystemManager instance;
    private final RandomAccessFile disk;
    private final ReentrantLock globalLock = new ReentrantLock();

    private static final int BLOCK_SIZE = 128; // Example block size

    private FEntry[] inodeTable; // Array of inodes
    private boolean[] freeBlockList; // Bitmap for free blocks

    
    // serialized map filename to byte[]
    private final Map<String, byte[]> files = new HashMap<>();

    //Locks for concurrency to allow multiple readers, single writer
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    //Backing file for persistence
    private final File storageFile;

    //Singleton instance management to limit inefficiency
    private static FileSystemManager instance = null; 

    









    public FileSystemManager(String filename, int totalSize) throws IOException, ClassNotFoundException {
        this.storageFile = new File(filename);

        //load existing data if present
        if (storageFile.exist() && storageFile.lenght() > 0){
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(storageFile))) {
                Object obj = ois.readObject();
                if (obj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, byte[] > loaded = (Map<String, byte[]>) obj;
                    files.putAll(loaded);
                }
            } 
            catch (Exception excep) {
                System.err.println("ERROR: The file could not load the file system state." + excep.getMessage());
            }
        }
        else {
            //make sure that a parent directory exist
            File parent = storageFile.getParentFile();
            if (parent != null && !parent.exists()); {
                parent.mkdirs();
            }
            persist(); // creating the base/ initial file
        }

        synchronized (FileSystemManager.class) {
            if (instance == null) {
                instance = this;
            }
        }
        
        

        /*
        

        // Initialize the file system manager with a file
        if(instance == null) {
            //TODO Initialize the file system
        } else {
            throw new IllegalStateException("FileSystemManager is already initialized.");
        }
        */
 
    }


    // Access to the singleton instance
    public static FileSystemManager getInstance() {
        return intance
    }

    private void persist() {
      File tmp = new File(storageFile.getAbsolutePath() + ".tmp");
      try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(tempFile))) {
          oos.writeObject(files);
          oos.flush();
          oos.getFD().sync(); // Ensure data is written to disk
      } 
      Files.move(tmp.toPath(), storageFile.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);

    }

    

    public void createFile(String fileName) throws Exception {
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("File name cannot be null or empty please write a name.");
        }
        if (fileName.length() > 11) {
            throw new Exception("File name is too long. Maximum length is 11 characters.");
        }
        rwLock.writeLock().lock();
        try {
            if (files.containsKey(fileName)) {
                throw new Exception("File " + fileName + "already exists.");
            }
            if
        }
    }

    
    // TODO: Add readFile, writeFile and other required methods,
}




