package main;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.*;

import com.pi4j.Pi4J;
import com.pi4j.io.serial.*;
import utils.Logger;
import utils.SerialHandler;

public class Main {
    /**
     * Ports:
     * Default Port (for receiving): 6000
     * Trust Send Port: 6001
     * Test Send Port: 6002
     */
    public static int PORT = 6000;
    public static int PORT_SEND_TRUST = 6001;
    public static int PORT_SEND_TEST = 6002;
    public static int MAX_BYTES = 4096;
    public String command = "IDLE";
    public InetAddress lastClientIP = InetAddress.getByName("0.0.0.0");
    public Integer lastClientPort = 0;
    public List<InetAddress> trustedDevices = new ArrayList<>();
    private File logFile;

    public Serial getSerial() {
        return serial;
    }

    public Serial serial;

    public File getLogFile() {
        return logFile;
    }

    public void setLogFile(File logFile) {
        this.logFile = logFile;
    }


    public Main() throws Exception {

    }

    public static void main(String[] args) throws Exception {
        boot();
    }

    private static void boot() throws Exception {
        Main main = new Main();
        var serial = main.serial;
        SerialHandler serialHandler = new SerialHandler();
        new Logger().createLogFile();
        var pi4j = Pi4J.newAutoContext();
        Logger.info("Trying to create Serial...");
        serial = pi4j.create(Serial.newConfigBuilder(pi4j)
                .use_115200_N81()
                .dataBits_8()
                .parity(Parity.NONE)
                .stopBits(StopBits._1)
                .flowControl(FlowControl.NONE)
                .id("my-serial")
                .device("UART1")
                .provider("pigpio-serial")
                .device("/dev/ttyS0")
                .build());
        serial.open();
        main.serial = serial;
        Logger.info("Serial created");
        Logger.info("Waiting for opening of Serial");
        while (!serial.isOpen()) {
            Thread.sleep(250);
        }
        Logger.info("Serial opened.");
        Logger.info("Starting Serial reader...");
        serialHandler.receive(serial);
        Logger.info("Successfully started Serial reader.");
        Logger.info("Trying Handshake with Controller...");
        serial.write("S");
        Logger.info("Trying to start server...");
        for (int i = 0; i < 9; i++) {
            try {
                main.server();
                Logger.info("Server started successfully!");
                break;
            } catch (Exception e) {
                if (i < 8) {
                    Logger.fatal("Failed to start server! Trying again...");
                    Logger.error("Stacktrace: " + e.getMessage());
                } else {
                    Logger.fatal("Failed to start server! RESET & REBOOT!");
                    Logger.error("Stacktrace: " + e.getMessage());
                    // TODO: Add Reset Function
                }
            }
        }
    }


    private void server() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                DatagramSocket socket = null;
                try {
                    socket = new DatagramSocket(Main.PORT);
                } catch (SocketException e) {
                    Logger.error("Failed to start server!\nStacktrace: " + e.getMessage());
                    return;
                }
                Logger.info("Server started. Listening for Clients on port " + Main.PORT + "...");
                byte[] buffer = new byte[Main.MAX_BYTES];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                while (true) {
                    try {
                        socket.receive(packet);
                    } catch (IOException e) {
                        break;
                    }
                    lastClientIP = packet.getAddress();
                    lastClientPort = packet.getPort();
                    String clientMessage = new String(packet.getData(),0,packet.getLength());
                    command = clientMessage;
                    Logger.info("[IP: " + lastClientIP + " | Port: " + lastClientPort +"] " + clientMessage);
                    Controller(lastClientIP);
                }
                if (socket.isBound()) socket.close();
            }
        });
        if(!thread.isAlive()) thread.start();
    }
  
    private void Controller(InetAddress ip){
        switch (command) {
            case "MFF" -> moveFF();
            case "MFR" -> moveFR();
            case "MFL" -> moveFL();
            case "MBB" -> moveBB();
            case "MBR" -> moveBR();
            case "MBL" -> moveBL();
            case "MRR" -> moveRR();
            case "MLL" -> moveLL();
            case "MTR" -> moveTR();
            case "MSS" -> moveSS();
            case "TRUST" -> connectionTrust(ip);
            case "CONNECT" -> startConnection(ip);
            case "CONNECTED" -> connectionTest(ip);
            case "IDLE" -> idle();
            case "SAY" -> say();
            case "REBOOT_NOW" -> rebootNow();
            case "REBOOT_S" -> rebootBySeconds(command);
            default -> Logger.warning("Command: " + command + " IS NOT VALID!");
        }
    }

    private void moveFF() {
        send("D[ff]");
    }

    private void moveFR() {
        send("D[fr]");
    }

    private void moveFL() {
        send("D[fl]");
    }

    private void moveBB() {
        send("D[bb]");
    }

    private void moveBR() {
        serial.write("BR");
    }

    private void moveBL() {
        serial.write("BL");
    }

    private void moveRR() {
        serial.write("D[rr]");
    }

    private void moveLL() {
        serial.write("LL");
    }

    private void moveTR() {
        serial.write("TR");
    }

    private void moveSS() {
        send("D[ss]");
    }

    private void connectionTrust(InetAddress ip) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean trust = trustedDevices.contains(ip);
                if (!trust) {
                    Scanner input = new Scanner(System.in);
                    while (true) {
                        System.out.print("Do you want to trust this IP? (Y/N): '" + ip + "': ");
                        String cmd = input.nextLine();
                        if (Objects.equals(cmd, "Y") || Objects.equals(cmd, "y")) {
                            trustedDevices.add(ip);
                            Logger.info("Now Trusting: " + ip);
                            trust = true;
                            break;
                        } else if (Objects.equals(cmd, "N") || Objects.equals(cmd, "n")) {
                            break;
                        } else {
                            System.out.println("Wrong Input! Try Again");
                        }
                    }
                }
                DatagramPacket sendPacket;
                DatagramSocket clientSocket;
                byte[] sendData = new byte[0];
                try {
                    clientSocket = new DatagramSocket();
                    clientSocket.setSoTimeout(1000);
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }
                try {
                    String string;
                    if (trust) {
                        string = "true";
                    } else {
                        string = "false";
                    }
                    sendData = string.getBytes();
                    Logger.debug("Sending Ack to: " + ip + ":" + PORT_SEND_TRUST);
                    sendPacket = new DatagramPacket(sendData, sendData.length, ip, PORT_SEND_TRUST);
                    Logger.debug("Sent Ack!");
                    clientSocket.send(sendPacket);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (clientSocket.isBound()) clientSocket.close();
                }
            }
        });
        if (!(thread.isAlive())) thread.start();
    }

    private void startConnection(InetAddress ip) {

    }

    private void connectionTest(InetAddress ip) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (trustedDevices.contains(ip)) {
                    DatagramPacket sendPacket;
                    DatagramSocket clientSocket;
                    byte[] sendData = new byte[0];
                    try {
                        clientSocket = new DatagramSocket();
                        clientSocket.setSoTimeout(1000);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return;
                    }
                    try {
                        String string = "connected";
                        sendData = string.getBytes();
                        sendPacket = new DatagramPacket(sendData, sendData.length, ip, PORT_SEND_TEST);
                        clientSocket.send(sendPacket);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        if (clientSocket.isBound()) clientSocket.close();
                    }
                }
            }
        });
        if (!(thread.isAlive())) thread.start();
    }

    private void idle() {
        Logger.info("I'm now Idle.");
    }

    private void forward() {
        Logger.info("I'm now going Forward.");
    }

    private void left() {
        Logger.info("I'm now turning Left.");
    }

    private void right() {
        Logger.info("I'm now turning Right.");
    }

    private void backwards() {
        Logger.info("I'm now going Backwards.");
    }
    private void say() {

    }

    private void rebootNow() {
        try  {
            Runtime r = Runtime.getRuntime();
            Process p = r.exec("reboot now");
            int exitCode = p.waitFor();

            if (exitCode == 0) {
                Logger.info("Now Rebooting");
            } else {
                Logger.error("Failed to Reboot! Exit Code: " + exitCode);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Logger.error("Failed to Reboot!");
        }
    }

    private void rebootBySeconds(String command) {
        try  {
            Runtime r = Runtime.getRuntime();
            int seconds = 1;
            Process p = r.exec("reboot " + seconds);
            int exitCode = p.waitFor();

            if (exitCode == 0) {
                Logger.info("Now Rebooting...");
                serial.write("R");
            } else {
                Logger.error("Failed to Reboot! Exit Code: " + exitCode);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Logger.error("Failed to Reboot!");
        }
    }


    public void send(String send) {
        serial.write(send);
    }
}