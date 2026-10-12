import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class Main {
  public static void main(String[] args) {
    // You can use print statements as follows for debugging, they'll be visible when running tests.
    System.out.println("Logs from your program will appear here!");

    try {
      ServerSocket serverSocket = new ServerSocket(4221);

      // Since the tester restarts your program quite often, setting SO_REUSEADDR
      // ensures that we don't run into 'Address already in use' errors
      serverSocket.setReuseAddress(true);

      while (true) {
        Socket clientSocket = serverSocket.accept();
        Thread clientThread = new Thread(() -> handleClient(clientSocket));
        clientThread.start();
      }
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }

  private static void handleClient(Socket clientSocket) {
    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
      String requestLine = reader.readLine();

      String status = "404 Not Found";
      String responseBody = "";
      String userAgent = "";
      String path = getPathFromRequestLine(requestLine);

      if ("/".equals(path)) {
        status = "200 OK";
      } else if (path.startsWith("/echo/")) {
        status = "200 OK";
        responseBody = path.substring("/echo/".length());
      } else if ("/user-agent".equals(path)) {
        status = "200 OK";
      }

      if ("/user-agent".equals(path)) {
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
          if (line.startsWith("User-Agent:")) {
            userAgent = line.substring("User-Agent:".length()).trim();
            break;
          }
        }
        responseBody = userAgent;
      }

      byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);
      String response = "HTTP/1.1 " + status + "\r\n" +
          "Content-Type: text/plain\r\n" +
          "Content-Length: " + bodyBytes.length + "\r\n\r\n" +
          responseBody;

      OutputStream outputStream = clientSocket.getOutputStream();
      outputStream.write(response.getBytes(StandardCharsets.UTF_8));
      outputStream.flush();
      clientSocket.close();
      System.out.println("accepted new connection");
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
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
}
