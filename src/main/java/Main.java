import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class Main {
  public static void main(String[] args) {
    // You can use print statements as follows for debugging, they'll be visible when running tests.
    System.out.println("Logs from your program will appear here!");

    String directory = ".";
    for (int i = 0; i < args.length; i++) {
      if ("--directory".equals(args[i]) && i + 1 < args.length) {
        directory = args[i + 1];
      }
    }

    try {
      ServerSocket serverSocket = new ServerSocket(4221);

      // Since the tester restarts your program quite often, setting SO_REUSEADDR
      // ensures that we don't run into 'Address already in use' errors
      serverSocket.setReuseAddress(true);

      while (true) {
        Socket clientSocket = serverSocket.accept();
        final String rootDirectory = directory;
        Thread clientThread = new Thread(() -> handleClient(clientSocket, rootDirectory));
        clientThread.start();
      }
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }

  private static void handleClient(Socket clientSocket, String directory) {
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
      String requestLine = reader.readLine();
      String path = getPathFromRequestLine(requestLine);

      String status = "404 Not Found";
      String responseBody = "";
      String contentType = "text/plain";
      String method = getMethodFromRequestLine(requestLine);

      if ("GET".equals(method) && "/".equals(path)) {
        status = "200 OK";
      } else if ("GET".equals(method) && path.startsWith("/echo/")) {
        status = "200 OK";
        responseBody = path.substring("/echo/".length());
      } else if ("GET".equals(method) && "/user-agent".equals(path)) {
        status = "200 OK";
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
          if (line.startsWith("User-Agent:")) {
            responseBody = line.substring("User-Agent:".length()).trim();
            break;
          }
        }
      } else if (path.startsWith("/files/")) {
        String fileName = path.substring("/files/".length());
        Path filePath = Path.of(directory, fileName);

        if ("GET".equals(method)) {
          if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
            status = "200 OK";
            responseBody = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
            contentType = "application/octet-stream";
          } else {
            status = "404 Not Found";
            responseBody = "";
          }
        } else if ("POST".equals(method)) {
          int contentLength = getContentLength(reader);
          String requestBody = readBody(reader, contentLength);
          Files.write(filePath, requestBody.getBytes(StandardCharsets.UTF_8));
          status = "201 Created";
          responseBody = "";
        }
      }

      byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);
      String response = "HTTP/1.1 " + status + "\r\n" +
          "Content-Type: " + contentType + "\r\n" +
          "Content-Length: " + bodyBytes.length + "\r\n\r\n" +
          responseBody;

      if ("404 Not Found".equals(status) && responseBody.isEmpty()) {
        response = "HTTP/1.1 404 Not Found\r\n\r\n";
      } else if ("201 Created".equals(status) && responseBody.isEmpty()) {
        response = "HTTP/1.1 201 Created\r\n\r\n";
      }

      OutputStream outputStream = clientSocket.getOutputStream();
      outputStream.write(response.getBytes(StandardCharsets.UTF_8));
      outputStream.flush();
      clientSocket.close();
      System.out.println("accepted new connection");
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }

  private static String getMethodFromRequestLine(String requestLine) {
    if (requestLine == null) {
      return "";
    }
    String[] parts = requestLine.split(" ");
    if (parts.length >= 2) {
      return parts[0];
    }
    return "";
  }

  private static String getPathFromRequestLine(String requestLine) {
    if (requestLine == null) {
      return "";
    }
    String[] parts = requestLine.split(" ");
    if (parts.length >= 2) {
      return parts[1];
    }
    return "";
  }

  private static int getContentLength(BufferedReader reader) throws IOException {
    String line;
    while ((line = reader.readLine()) != null && !line.isEmpty()) {
      if (line.startsWith("Content-Length:")) {
        return Integer.parseInt(line.substring("Content-Length:".length()).trim());
      }
    }
    return 0;
  }

  private static String readBody(BufferedReader reader, int contentLength) throws IOException {
    char[] buffer = new char[contentLength];
    int totalRead = 0;
    while (totalRead < contentLength) {
      int read = reader.read(buffer, totalRead, contentLength - totalRead);
      if (read == -1) {
        break;
      }
      totalRead += read;
    }
    return new String(buffer, 0, totalRead);
  }
}
