package com.burym;

public class Main {
    public static void main(String[] args) {
       // Socks4Proxy server = new Socks4Proxy(10897, "127.0.0.1");
        Socks5Proxy server = new Socks5Proxy(10897, "127.0.0.1");
        server.run();
//        try {
//            ResolvingDNS.lookupAddr("google.com");
//        } catch (TextParseException | UnknownHostException | InterruptedException e) {
//            throw new RuntimeException(e);
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        }
    }
}
