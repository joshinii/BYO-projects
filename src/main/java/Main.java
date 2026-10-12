import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
      InputStream inputStream = clientSocket.getInputStream();
      String requestLine = readLine(inputStream);
      String path = getPathFromRequestLine(requestLine);

      String status = "404 Not Found";
      String responseBody = "";
      String contentType = "text/plain";
      String method = getMethodFromRequestLine(requestLine);
      Map<String, String> headers = readHeaders(inputStream);
      String contentEncoding = "";
      String acceptEncoding = headers.getOrDefault("accept-encoding", "");

      if ("GET".equals(method) && "/".equals(path)) {
        status = "200 OK";
      } else if ("GET".equals(method) && path.startsWith("/echo/")) {
        status = "200 OK";
        responseBody = path.substring("/echo/".length());
      } else if ("GET".equals(method) && "/user-agent".equals(path)) {
        status = "200 OK";
        responseBody = headers.getOrDefault("user-agent", "");
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
          int contentLength = getContentLength(headers);
          byte[] requestBodyBytes = readBody(inputStream, contentLength);
          Files.write(filePath, requestBodyBytes);
          status = "201 Created";
          responseBody = "";
        }
      }

      if ("gzip".equalsIgnoreCase(acceptEncoding) || acceptEncoding.toLowerCase().contains("gzip")) {
        contentEncoding = "gzip";
      }

      byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);
      StringBuilder headersBuilder = new StringBuilder();
      headersBuilder.append("HTTP/1.1 ").append(status).append("\r\n");
      headersBuilder.append("Content-Type: ").append(contentType).append("\r\n");
      headersBuilder.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
      if (!contentEncoding.isEmpty()) {
        headersBuilder.append("Content-Encoding: ").append(contentEncoding).append("\r\n");
      }
      headersBuilder.append("\r\n");

      String response = headersBuilder + responseBody;

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

  private static Map<String, String> readHeaders(InputStream inputStream) throws IOException {
    Map<String, String> headers = new java.util.HashMap<>();
    String line;
    while ((line = readLine(inputStream)) != null && !line.isEmpty()) {
      int colonIndex = line.indexOf(':');
      if (colonIndex != -1) {
        String key = line.substring(0, colonIndex).trim().toLowerCase();
        String value = line.substring(colonIndex + 1).trim();
        headers.put(key, value);
      }
    }
    return headers;
  }

  private static int getContentLength(Map<String, String> headers) {
    String value = headers.get("content-length");
    if (value == null) {
      return 0;
    }
    return Integer.parseInt(value.trim());
  }

  private static String readLine(InputStream inputStream) throws IOException {
    StringBuilder line = new StringBuilder();
    while (true) {
      int byteValue = inputStream.read();
      if (byteValue == -1) {
        return line.length() == 0 ? null : line.toString();
      }
      if (byteValue == '\r') {
        int next = inputStream.read();
        if (next == '\n') {
          return line.toString();
        }
        line.append((char) byteValue);
        if (next != -1) {
          line.append((char) next);
        }
        continue;
      }
      if (byteValue == '\n') {
        return line.toString();
      }
      line.append((char) byteValue);
    }
  }

  private static byte[] readBody(InputStream inputStream, int contentLength) throws IOException {
    byte[] buffer = new byte[contentLength];
    int totalRead = 0;
    while (totalRead < contentLength) {
      int read = inputStream.read(buffer, totalRead, contentLength - totalRead);
      if (read == -1) {
        break;
      }
      totalRead += read;
    }
    return java.util.Arrays.copyOf(buffer, totalRead);
  }
}
