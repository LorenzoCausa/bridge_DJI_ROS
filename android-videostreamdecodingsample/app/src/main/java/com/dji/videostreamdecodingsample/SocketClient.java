package com.dji.videostreamdecodingsample;


import android.os.AsyncTask;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

public class SocketClient {
    int port;
    DatagramSocket socket;
    InetAddress address;

    //Constructor Declaration of Class
    public SocketClient(String ip, int port) {
        String ip_address = ip;

        DatagramSocket Asocket;
        InetAddress Aaddress;
        try {
            Asocket = new DatagramSocket();
        } catch (SocketException e) {
            e.printStackTrace();
            Asocket = null;
        }
        try {
            Aaddress = InetAddress.getByName(ip_address);
        } catch (UnknownHostException e) {
            e.printStackTrace();
            Aaddress = null;
        }

        this.port = port;
        this.address=Aaddress;
        this.socket=Asocket;
    }
    public void execute(byte[] buf){
        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        try {
            socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

}