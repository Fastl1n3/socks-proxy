package com.burym;

import org.xbill.DNS.lookup.LookupResult;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;

import java.util.Iterator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class Socks5Proxy implements Runnable {
    private final int BUFFER_SIZE = 8192;
    private final int PORT;
    private final String HOST;

    static final byte[] OK = new byte[] {0x05, 0x00, 0x00, 0x01,
            0x01, 0x01, 0x01, 0x01,
            0x00, 0x50};

    static final byte[]  GREETING_OK = new byte[] {0x05, 0x00};


    public Socks5Proxy(int port, String host) {
        PORT = port;
        HOST = host;
    }

    static class Attachment {
        ByteBuffer in;
        ByteBuffer out;
        SelectionKey peer;
        boolean isSecondHeader = false;
    }

    @Override
    public void run() {
        try (Selector selector = SelectorProvider.provider().openSelector();
             ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
            serverChannel.configureBlocking(false);
            serverChannel.socket().bind(new InetSocketAddress(HOST, PORT));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            while(selector.select() > -1) {
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (key.isValid()) {
                        try {
                            if (key.isAcceptable()) {
                                accept(key);
                            }
                            else if (key.isConnectable()) {
                                connect(key);
                            }
                            else if (key.isReadable()) {
                                read(key);
                            }
                            else if (key.isWritable()) {
                                write(key);
                            }
                        }
                        catch (Exception e) {
                            System.out.println("ERROR: " + e.getMessage());
                            //e.printStackTrace();
                            close(key);
                        }
                    }
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

    }


    private void read(SelectionKey key) throws IOException, ExecutionException, InterruptedException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        Attachment attachment = (Attachment) key.attachment();
        if (attachment == null) {
            attachment = new Attachment();
            key.attach(attachment);
            attachment.in = ByteBuffer.allocate(BUFFER_SIZE);
            //  attachment.out = ByteBuffer.allocate(BUFFER_SIZE);
        }
        System.out.println("Reading...");
        int a= socketChannel.read(attachment.in);
        if (a < 1) {
            System.out.println("XEP: " + a);
            close(key);
        }
        else if (attachment.peer == null) { // значит это хедер
            if (!attachment.isSecondHeader) {
                readGreeting(key, attachment);
            }
            else {
                readHeader(key, attachment);
            }
        }
        else { // проксируем, добавляем ко 2му концу интерес на запись
            attachment.peer.interestOps(attachment.peer.interestOps() | SelectionKey.OP_WRITE);
            key.interestOps(key.interestOps() ^ SelectionKey.OP_READ); //убираем у нашего клиента чтение
            attachment.in.flip(); //готовим буфер для записи
        }
    }

    private void readGreeting(SelectionKey key, Attachment attachment) throws IOException, IllegalStateException {
        System.out.println("Reading greeting: ");
        byte[] arrIn = attachment.in.array();
       // System.out.println(Arrays.toString(arrIn));
        if (arrIn[0] != 5 || arrIn[1] < 1) {
            System.out.println("version: " + arrIn[0] + " kol-vo auths: " + arrIn[1]);
            throw new IllegalStateException("Bad request");
        }
        else {
            ((SocketChannel)key.channel()).write(ByteBuffer.wrap(GREETING_OK));

            key.interestOps(key.interestOps() | SelectionKey.OP_READ);
            attachment.isSecondHeader = true;
            attachment.in.clear();
        }

    }

    private void readHeader(SelectionKey key, Attachment attachment) throws IOException, IllegalStateException, ExecutionException, InterruptedException {
        System.out.println("Reading header: ");
        byte[] arrIn = attachment.in.array();
        byte version = arrIn[0];
        byte cmdCode = arrIn[1];
        byte atype = arrIn[3];
      //  System.out.println("Array: " + Arrays.toString(arrIn));
        System.out.println("version: " + version + " cmd code: " + cmdCode + " atype: " + atype);

        if (version != 5 && cmdCode != 1 || attachment.in.position() < 9) {
            System.out.println("BAD PROTOCOL version: " + version + " cmdCode: " + cmdCode + " atype: " + atype);
            throw new IllegalStateException("Bad request");
        }
        else {
            byte[] addr;
            int port;
            if (atype == 3) {
                System.out.println("3 type!!!!!!!!!!");
                int len = arrIn[4];

                Future<LookupResult> resolverResult = ResolvingDNS.lookupAddr(new String(arrIn, 5, len));
                addr = resolverResult.get().getRecords().get(0).rdataToWireCanonical();
                port = ((255 & arrIn[5 + len]) << 8) + (255 & arrIn[5 + len + 1]);
            }
            else {
                addr = new byte[]{arrIn[4], arrIn[5], arrIn[6], arrIn[7]};
                port = ((255 & arrIn[8]) << 8) + (255 & arrIn[9]);
                System.out.println("IP: " + InetAddress.getByAddress(new byte[]{arrIn[4], arrIn[5], arrIn[6], arrIn[7]}) + " PORT: " + (((255 & arrIn[8]) << 8) + (255 & arrIn[9])));
            }
            SocketChannel peer = SocketChannel.open();
            peer.configureBlocking(false);
            peer.connect(new InetSocketAddress(InetAddress.getByAddress(addr), port));
            System.out.println("Header has read. " + InetAddress.getByAddress(addr) + " " + port);
            SelectionKey peerKey = peer.register(key.selector(), SelectionKey.OP_CONNECT); // внешняя сторона теперь ждет коннекта
            key.interestOps(0); // глушим

            attachment.peer = peerKey; // присвоили ключ соединения, куда обращается клиент(2ой конец)
            Attachment attachmentPeer = new Attachment();
            attachmentPeer.peer = key; // присвоили ключ соединения клиента 2му концу
            peerKey.attach(attachmentPeer);
            attachment.in.clear();
        }
    }


    private void connect(SelectionKey key) throws IOException {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        System.out.println("connect: " + socketChannel.socket().getInetAddress() + " : " + socketChannel.socket().getPort());
        Attachment attachment = (Attachment) key.attachment();
        socketChannel.finishConnect(); //завершить неблокирующее соединение

        attachment.in = ByteBuffer.allocate(BUFFER_SIZE);// ,буфер для чтения у внешней стороны,
        attachment.in.put(OK).flip(); //
        attachment.out = ((Attachment)attachment.peer.attachment()).in; //связываем A.out = B.in
        ((Attachment) attachment.peer.attachment()).out = attachment.in;

        attachment.peer.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE); //ставим 2 концу флаги, когда она запишет ОК, переключит на чтение
        key.interestOps(0); //глушим
    }

    private void write(SelectionKey key) throws IOException {
        System.out.println("Writing");
        SocketChannel socketChannel = (SocketChannel) key.channel();
        Attachment attachment = (Attachment) key.attachment();
        if (socketChannel.write(attachment.out) == -1) {
            close(key);
        }
        else if (attachment.out.remaining() == 0) { //если все записано
            if (attachment.peer == null) {
                close(key);
            }
            else {
                attachment.out.clear();
                attachment.peer.interestOps(attachment.peer.interestOps() | SelectionKey.OP_READ); //добавляем у 2го конца интерес читать
                key.interestOps(key.interestOps() ^ SelectionKey.OP_WRITE); // у нашего убираем интерес писать

            }
        }
    }

    private void accept(SelectionKey key) throws IOException { //для сервер сокета
        System.out.println("Accept new connection...");
        SocketChannel socketChannel = ((ServerSocketChannel)key.channel()).accept();
        socketChannel.configureBlocking(false);
        socketChannel.register(key.selector(), SelectionKey.OP_READ); //ставим read и можем идти читать хедер
    }

    private void close(SelectionKey key) throws IOException {
        System.out.println("Closing...");
        key.cancel();
        key.channel().close();
        SelectionKey peerKey = ((Attachment)key.attachment()).peer;
        if (peerKey != null) {
            ((Attachment)peerKey.attachment()).peer = null;
            if ((peerKey.interestOps() & SelectionKey.OP_WRITE) == 0) { //нет интереса писать
                if (((Attachment) peerKey.attachment()).out != null) {
                    ((Attachment) peerKey.attachment()).out.flip();
                }
            }
            peerKey.interestOps(SelectionKey.OP_WRITE);
        }
    }
}
