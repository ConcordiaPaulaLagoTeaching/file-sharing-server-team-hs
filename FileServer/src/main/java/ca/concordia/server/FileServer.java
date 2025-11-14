package ca.concordia.server;
import ca.concordia.filesystem.FileSystemManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * ASSIGNMENT PART: Server/Client Architecture and Sockets
 * This class implements the file-sharing server that handles client connections via sockets.
 */
public class FileServer {

    private FileSystemManager fsManager;
    private int port;
    private String fileSystemName;
    private int totalSize;
    private Exception initializationError;
    
    public FileServer(int port, String fileSystemName, int totalSize) {
        // Store parameters for lazy initialization
        this.port = port;
        this.fileSystemName = fileSystemName;
        this.totalSize = totalSize;
        this.initializationError = null;
        
        // Try to initialize FileSystemManager, but don't throw exception
        try {
            this.fsManager = new FileSystemManager(fileSystemName, totalSize);
        } catch (Exception e) {
            this.initializationError = e;
            this.fsManager = null;
        }
    }

    public void start(){
        // Check if initialization failed
        if (initializationError != null) {
            System.err.println("Failed to initialize file system: " + initializationError.getMessage());
            initializationError.printStackTrace();
            return;
        }
        
        // If fsManager is still null, try to initialize it now
        if (fsManager == null) {
            try {
                this.fsManager = new FileSystemManager(fileSystemName, totalSize);
            } catch (Exception e) {
                System.err.println("Failed to initialize file system: " + e.getMessage());
                e.printStackTrace();
                return;
            }
        }
        
        // ASSIGNMENT PART: Server/Client Architecture - Create server socket
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server started. Listening on port " + port + "...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connected: " + clientSocket.getRemoteSocketAddress());
                
                // ASSIGNMENT PART: Multithreading - Create new thread for each client
                // This allows server to handle multiple clients concurrently
                ClientHandler handler = new ClientHandler(clientSocket, fsManager);
                Thread clientThread = new Thread(handler);
                clientThread.start();
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Could not start server on port " + port);
        }
    }
    
    /**
     * ASSIGNMENT PART: Multithreading
     * ClientHandler class - Each client connection runs in its own thread.
     * This implements Runnable to allow concurrent handling of multiple clients.
     */
    private static class ClientHandler implements Runnable {
        private Socket clientSocket;
        private FileSystemManager fsManager;
        
        public ClientHandler(Socket clientSocket, FileSystemManager fsManager) {
            this.clientSocket = clientSocket;
            this.fsManager = fsManager;
        }
        
        @Override
        public void run() {
            try (
                    BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)
            ) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("Received from client " + clientSocket.getRemoteSocketAddress() + ": " + line);
                    
                    try {
                        // Handle empty line - always respond with error, don't close connection
                        if (line == null || line.trim().isEmpty()) {
                            writer.println("ERROR: Empty command.");
                            writer.flush();
                            continue;
                        }
                        
                        String[] parts = line.split(" ", 3); // Split into max 3 parts for WRITE command
                        if (parts.length == 0 || (parts.length > 0 && (parts[0] == null || parts[0].trim().isEmpty()))) {
                            writer.println("ERROR: Empty command.");
                            writer.flush();
                            continue;
                        }
                        
                        String command = parts[0].toUpperCase();
                        String response = processCommand(command, parts);
                        writer.println(response);
                        writer.flush();
                        
                        // Handle QUIT command
                        if ("QUIT".equals(command)) {
                            break;
                        }
                    } catch (Exception e) {
                        // ASSIGNMENT PART: Error Handling
                        // Server displays errors to client but continues serving requests.
                        // Client can send another request after an error.
                        try {
                            String errorMsg = e.getMessage();
                            if (errorMsg != null && errorMsg.startsWith("ERROR:")) {
                                writer.println(errorMsg);
                            } else {
                                writer.println("ERROR: " + (errorMsg != null ? errorMsg : "Unknown error"));
                            }
                            writer.flush();
                        } catch (Exception ex) {
                            // If we can't send error, just log it but don't break the loop
                            System.err.println("Error sending error message: " + ex.getMessage());
                        }
                        System.err.println("Error processing command: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.err.println("Error handling client " + clientSocket.getRemoteSocketAddress() + ": " + e.getMessage());
                e.printStackTrace();
            } finally {
                try {
                    clientSocket.close();
                    System.out.println("Client disconnected: " + clientSocket.getRemoteSocketAddress());
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
        
        /**
         * ASSIGNMENT PART: Server Commands
         * Processes client commands: CREATE, WRITE, READ, DELETE, LIST
         */
        private String processCommand(String command, String[] parts) throws Exception {
            switch (command) {
                // ASSIGNMENT PART: CREATE command - Creates a new empty file
                case "CREATE":
                    if (parts.length < 2) {
                        throw new Exception("ERROR: CREATE command requires a filename");
                    }
                    String createFilename = parts[1];
                    if (createFilename.length() > 11) {
                        return "ERROR: filename too large";
                    }
                    fsManager.createFile(createFilename);
                    return "SUCCESS: File '" + createFilename + "' created.";
                    
                // ASSIGNMENT PART: WRITE command - Writes content to a file
                case "WRITE":
                    if (parts.length < 3) {
                        throw new Exception("ERROR: WRITE command requires filename and content");
                    }
                    String writeFilename = parts[1];
                    if (writeFilename.length() > 11) {
                        return "ERROR: filename too large";
                    }
                    String content = parts[2];
                    fsManager.writeFile(writeFilename, content.getBytes());
                    return "SUCCESS: File '" + writeFilename + "' written.";
                    
                // ASSIGNMENT PART: READ command - Reads and returns file contents
                case "READ":
                    if (parts.length < 2) {
                        throw new Exception("ERROR: READ command requires a filename");
                    }
                    String readFilename = parts[1];
                    byte[] fileContent = fsManager.readFile(readFilename);
                    return "SUCCESS: " + new String(fileContent);
                    
                // ASSIGNMENT PART: DELETE command - Deletes a file
                case "DELETE":
                    if (parts.length < 2) {
                        throw new Exception("ERROR: DELETE command requires a filename");
                    }
                    String deleteFilename = parts[1];
                    fsManager.deleteFile(deleteFilename);
                    return "SUCCESS: File '" + deleteFilename + "' deleted.";
                    
                // ASSIGNMENT PART: LIST command - Lists all files in the system
                case "LIST":
                    String[] files = fsManager.listFiles();
                    if (files.length == 0) {
                        return "SUCCESS: No files in the system.";
                    }
                    StringBuilder fileList = new StringBuilder("SUCCESS: ");
                    for (int i = 0; i < files.length; i++) {
                        fileList.append(files[i]);
                        if (i < files.length - 1) {
                            fileList.append(", ");
                        }
                    }
                    return fileList.toString();
                    
                case "QUIT":
                    return "SUCCESS: Disconnecting.";
                    
                default:
                    return "ERROR: Unknown command.";
            }
        }
    }
}
