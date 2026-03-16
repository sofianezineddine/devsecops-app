package com.example;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class App {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(
            System.getenv().getOrDefault("PORT", "15000"));
        HttpServer server = HttpServer.create(
            new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> {
            String response = "Hello, DevSecOps From Sofiane Zine-eddine!";
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        });
        server.start();
        System.out.println("Server running on port " + port);
    }
}
