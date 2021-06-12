import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class flightHandler extends Thread{

    // Addresse der Drone
    private final String ipAddress = "192.168.10.1";
    private Inet4Address ia;

    // Kanal für Kommandos und deren ACKs
    private final int commandPort = 8889;
    private DatagramSocket out = null;
    private DatagramPacket commandPacket = null;
    private DatagramPacket ACK_packet = null;

    // Bytepuffer für Packete
    private byte[] packetData;
    private final int packetDataSize = 1024;

    // Liste der Kommandos und aktuelles Kommando
    private ArrayList<String> commands;
    private String command;

    // Statusmeldung sieht Benutzer
    private volatile String state = "Initialisiere ...";

    // Logging
    private Logger logger;
    private Handler handler;
    private Object lock = new Object(); // Sperre die exklusiven Schreibzugriff auf logger verwirklicht

    // innere Klasse statusHandler
    private class statusHandler extends Thread{

        // Kanal für Statusabfragen
        private final int statusPort = 8890;
        private DatagramSocket in = null;
        private DatagramPacket statusPacket = null;

        // --- Statusvariablen ---
        // Geschwindigkeit(en)
        private int Vx = 0;
        private int Vy = 0;
        private int Vz = 0;
        private double speed;

        // Beschleunigung(en)
        private int Ax = 0;
        private int Ay = 0;
        private int Az = 0;
        private double accleration;

        // Flugwinkel
        private int nicken = 0;
        private int rollen = 0;
        private int gieren = 0;

        // Batterieladung (in Prozent)
        private int bat = 0;

        // kann durch flightHandler gesetzt werden
        private boolean alive = true;

        public void statusHandler(){ }

        // wird vom FlightHandler gerufen, wenn der StatusHandler zum Ende kommen soll
        public void end(){
            alive = false;
            synchronized (lock){logger.info("StatusHandler durch FlightHandler beendet");}
        }

        // Nachricht aus Statuskanal zerlegen und Statusvariablen ueberschreiben
        private void calculateState(String m){

            try {
                String parts[] = m.split("pitch:");
                parts = parts[1].split(";");
                nicken = Integer.parseInt(parts[0]);

                parts = m.split("roll:");
                parts = parts[1].split(";");
                rollen = Integer.parseInt(parts[0]);

                parts = m.split("yaw:");
                parts = parts[1].split(";");
                gieren = Integer.parseInt(parts[0]);

                parts = m.split("vgx:");
                parts = parts[1].split(";");
                Vx = Integer.parseInt(parts[0]);

                parts = m.split("vgy:");
                parts = parts[1].split(";");
                Vy = Integer.parseInt(parts[0]);

                parts = m.split("vgz:");
                parts = parts[1].split(";");
                Vz = Integer.parseInt(parts[0]);

                speed = Math.sqrt(Vx * Vx + Vy * Vy + Vz * Vz);

                parts = m.split("agx:");
                parts = parts[1].split(";");
                Ax = Integer.parseInt(parts[0]);

                parts = m.split("agy:");
                parts = parts[1].split(";");
                Ay = Integer.parseInt(parts[0]);

                parts = m.split("agz:");
                parts = parts[1].split(";");
                Az = Integer.parseInt(parts[0]);

                accleration = Math.sqrt(Ax * Ax + Ay * Ay + Az * Az);

                parts = m.split("bat:");
                parts = parts[1].split(";");
                bat = Integer.parseInt(parts[0]);
            } catch (Exception e){
                synchronized (lock){logger.warning("Statusnachricht der Drone nicht verstanden");}
            }
        }

        @Override
        public void run(){

            Thread.currentThread().setName("StatusHandler");

            // --- Status - Handling - Kanal ---
            // UDP Socket oeffnen
            try{
                in = new DatagramSocket(statusPort);
                in.setSoTimeout(100);
            }catch (SocketException se){
                synchronized (lock){
                    logger.severe("Bekomme kein Datagram-Socket zum Abfragen des Status auf Port " + statusPort);
                }
                alive = false;
            }

            while(alive){
                try {

                    statusPacket = new DatagramPacket(new byte[packetDataSize], packetDataSize);
                    in.receive(statusPacket);
                    System.out.println("STATUS HANDLER BLOCKIERT NICHT");
                    calculateState(new String(statusPacket.getData()));

                    state = "Geschw: " + speed + "( " + Vx + ", " + Vy + ", " + Vz + ") " + "Beschl: " + accleration +
                            "Nicken: " + nicken + ", Rollen: " + rollen + ", Gieren: " + gieren + ", Batterie: " + bat;

                    synchronized (lock) {
                        logger.info(state);
                    }

                }catch (SocketTimeoutException stoe){
                        continue;
                } catch (IOException ioe) {
                    in.close();
                    synchronized (lock){logger.severe("Kann keinen Status empfangen");}
                }
            }

            if(in != null)
                in.close();
        }
    }
    // ENDE innere Klasse statusHandler

    // Instanz des statusHandlers
    statusHandler sh;

    // --- Konstruktor ---
    public flightHandler(){
        try {
            logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
            logger.setLevel(Level.ALL);
            handler = new FileHandler("log.txt");
            handler.setLevel(Level.ALL);
            logger.addHandler(handler);
            logger.info("neue Mission");
        } catch (IOException ioe){
            ioe.printStackTrace();
            System.exit(-1);
        }
    }

    // Sockets für beide Kanäle öffnen, Drone mit 'command' initialisieren und auf ACK warten
    private boolean connect(){

        // --- Command - Handling - Kanal ---
        // IP auflösen
        try {
            ia = (Inet4Address) Inet4Address.getByName(ipAddress);
        } catch (UnknownHostException uhe) {
            synchronized (lock) {
                logger.severe("IP-Addresse der Drohne (" + ipAddress + ": " + ia + ") lässt sich nicht auflösen");
            }
            return false;
        }

        // Socket oeffnen
        try { out = new DatagramSocket(commandPort); }
        catch (SocketException se) {
            synchronized (lock){
                logger.severe("Bekomme kein Datagram-Socket zum Versenden der Kommandos auf Port " + commandPort);
            }
            return false;
        }

        // Steuerung initialisieren
        command = "command";
        packetData = command.getBytes();
        commandPacket = new DatagramPacket(packetData, packetData.length, ia, commandPort);

        try {
            ACK_packet = new DatagramPacket(new byte[packetDataSize], packetDataSize);

            //5 s auf Bestätigung warten
            out.setSoTimeout(5000);
            out.send(commandPacket);
            out.receive(ACK_packet);

            String msg = new String(ACK_packet.getData(), 0, ACK_packet.getLength());

            if (msg.equals("ok")) {
                synchronized (lock){
                    logger.info(command + " ok");
                }
            }else{
                synchronized (lock){
                    logger.info(command + " nicht ok: " + msg);
                }
                out.close();
                return false;
            }

        } catch (IOException ioe) {
            synchronized (lock){
                logger.severe("Kann Drohne nicht initialisieren ('command' kann nicht versendet werden)");
            }
            out.close();
            return false;
        }

        return true;
    }

    // Sockets im Fehlerfall schließen
    private void closeSockets(){
        synchronized (lock){
            logger.warning("Versuche Sockets zu schließen");
        }
        out.close();
    }

    // aktuelles Kommando rausschicken und 10 Versuche fuer ACK abwarten
    private void sendCommandAndAwaitAck() throws IOException{

        String msg;
        int trys = 0;

        out.send(commandPacket);

        while(true) {

            out.receive(ACK_packet);
            msg = new String(ACK_packet.getData(), 0, ACK_packet.getLength());

            if (msg.equals("ok")) {
                synchronized (lock){
                    logger.info(command + " ok");
                }
                break;
            } else {
                synchronized (lock){
                    logger.info(command + " nicht ok: " + msg);
                }
                trys++;
                if(trys > 10) {
                    synchronized (lock){
                        logger.severe(command + " nicht ok und Timeout überschritten -> Abbruch");
                    }
                    throw new IOException();
                }
            }
        }
    }

    // Abheben + uebergebene Kommandos abarbeiten
    private boolean pollCommands(){

        //0 Abheben und nach 500ms (0,5s) schweben
        command = "takeoff";
        packetData = command.getBytes();

        commandPacket = new DatagramPacket(packetData, packetData.length, ia, commandPort);
        ACK_packet = new DatagramPacket(new byte[packetDataSize], packetDataSize);

        try {

            sendCommandAndAwaitAck();

            // 500ms steigen lassen
            try { Thread.sleep(500); } catch (InterruptedException ie){
                closeSockets();
                synchronized (lock){
                    logger.severe("Fatal: sleep");
                }
                return false;
            }

            command = "stop";
            packetData = command.getBytes();

            commandPacket = new DatagramPacket(packetData, packetData.length, ia, commandPort);
            ACK_packet = new DatagramPacket(new byte[packetDataSize], packetDataSize);

            sendCommandAndAwaitAck();

        } catch (IOException ioe){
            closeSockets();
            synchronized (lock){
                logger.info(command + " nicht gesendet");
            }
            return false;
        }

        // alle anderen Kommandos abarbeiten
        for(int i = 0; i < commands.size(); i++){

            command = commands.get(i);
            packetData = command.getBytes();

            commandPacket = new DatagramPacket(packetData, packetData.length, ia, commandPort);
            ACK_packet = new DatagramPacket(new byte[packetDataSize], packetDataSize);

            try { sendCommandAndAwaitAck(); }
            catch (IOException ioe) {
                closeSockets();
                synchronized (lock){
                    logger.severe(command + " nicht gesendet");
                }
                return false;
            }
        }

        return true;
    }

    // Presenter setzt neue Kommandos aus Eingabe
    public void setCommands(ArrayList<String> commands){
        this.commands = commands;
        synchronized (lock){
            logger.info("neue Kommandos erhalten");
        }
    }

    // UpdateRequest des Presenters fragt Status ab
    public String getStatus(){ return state; }

    // Versuche Verbindung herzustellen und Versuche Kommandos abzuarbeiten
    // Thread-Methode durch Presenter aktiviert
    @Override
    public void run(){

        Thread.currentThread().setName("FlightHandler");

        // starte das Abfragen des Drohnenstatus im Thread statusHandler
        sh = new statusHandler();
        sh.start();

        if(!connect()) {
            state = "Bekomme keine Verbindung zur Drohne";
            logger.severe(state);
            sh.end();

            try { Thread.sleep(8000); }
            catch (InterruptedException ie){
                synchronized (lock){
                    logger.severe("Fatal sleep");
                }
            }
        } else {

            if (!pollCommands()) {
                state = "Kann Kommandos nicht abarbeiten";
                logger.severe(state);
                sh.end();

                try { Thread.sleep(8000); }
                catch (InterruptedException ie){
                    synchronized (lock){
                        logger.severe("Fatal sleep");
                    }
                }
            }
        }

        sh.end();
        try {
            sh.join();
        } catch (InterruptedException e) {
            synchronized (lock){
                logger.severe("Fatal join");
            }
        }
    }
}
