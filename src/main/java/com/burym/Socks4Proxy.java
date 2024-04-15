package com.burym;

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


public class Socks4Proxy implements Runnable{
    private final int BUFFER_SIZE = 8192 * 40;
    private final int PORT;
    private final String HOST;

    static final byte[] OK = new byte[] {0x00, 0x5a, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    public Socks4Proxy(int port, String host) {
        PORT = port;
        HOST = host;
    }
    static class Attachment {
        ByteBuffer in;
        ByteBuffer out;
        SelectionKey peer;
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
                            System.out.println(e.getMessage());
                            //e.printStackTrace();
                            close(key);
                        }
                    }
                }
            }
            
        } catch (IOException e) {
            e.printStackTrace();
            //throw new RuntimeException(e);
        }

    }


    private void read(SelectionKey key) throws IOException {
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
            readHeader(key, attachment);
        }
        else { // проксируем, добавляем ко 2му концу интерес на запись

            attachment.peer.interestOps(attachment.peer.interestOps() | SelectionKey.OP_WRITE);
            key.interestOps(key.interestOps() ^ SelectionKey.OP_READ); //убираем у нашего клиента чтение
            attachment.in.flip(); //готовим буфер для записи
         //   ((Attachment)attachment.peer.attachment()).out = ByteBuffer.allocate(BUFFER_SIZE);
        }
    }


    private void readHeader(SelectionKey key, Attachment attachment) throws IOException, IllegalStateException {
        System.out.println("Reading header: ");
        byte[] arrIn = attachment.in.array();
        System.out.println("version: " + arrIn[0] + " cmd code: " + arrIn[1] + " port: " + (((255 & arrIn[2]) << 8) + (255 & arrIn[3]))
                + " ip: " + InetAddress.getByAddress(new byte[]{arrIn[4], arrIn[5], arrIn[6], arrIn[7]}));
        if (arrIn[attachment.in.position() - 1] == 0) {
            if (arrIn[0] != 4 && arrIn[1] != 1 || attachment.in.position() < 8) {
                System.out.println("version: " + arrIn[0] + " m: " + arrIn[1]);
                throw new IllegalStateException("Bad request");
            }
            else {
                SocketChannel peer = SocketChannel.open();
                peer.configureBlocking(false);
                byte[] addr = new byte[] {arrIn[4], arrIn[5], arrIn[6], arrIn[7]};
                int port = ((255 & arrIn[2]) << 8) + (255 & arrIn[3]);

                peer.connect(new InetSocketAddress(InetAddress.getByAddress(addr), port));
                System.out.println("Header has read. " + InetAddress.getByAddress(addr) + " " + port);
                SelectionKey peerKey = peer.register(key.selector(), SelectionKey.OP_CONNECT); // внешняя сторона теперь ждет коннекта
                key.interestOps(0); // глушим

                attachment.peer = peerKey; // присвоили ключ соединения, куда обращается клиент(2ой конец)
                Attachment attachmentPeer = new Attachment();
                //attachmentPeer.in =  ByteBuffer.allocate(BUFFER_SIZE);
                //attachment.out = attachmentPeer.in;
                attachmentPeer.peer = key; // присвоили ключ соединения клиента 2му концу
                peerKey.attach(attachmentPeer);
                attachment.in.clear();
            }
        }
    }




    private void connect(SelectionKey key) throws IOException {


        SocketChannel socketChannel = (SocketChannel) key.channel();
        System.out.println("connect: " + socketChannel.socket().getLocalAddress() + " " + socketChannel.socket().getInetAddress());
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
                ((Attachment) peerKey.attachment()).out.flip();
            }
            peerKey.interestOps(SelectionKey.OP_WRITE);
        }
    }
}
