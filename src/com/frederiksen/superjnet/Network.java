package com.frederiksen.superjnet;

import com.sun.xml.internal.bind.v2.runtime.reflect.Lister;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Stack;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * TCP/UDP network manager
 */
public class Network {
    private boolean server;
    private boolean running;

    /* server stuff */
    private ServerSocket serverSocket;
    private Thread serverListener;
    private Thread beacon;
    private DatagramSocket beaconSocket;
    private final ArrayList<Remote> remotes = new ArrayList<>();

    /* client stuff */
    private Remote remoteServer;

    /* common stuff */
    private RemoteAddress localAddress;
    private UdpSocket udpSocket;
    private int udpBufferSize;

    private Thread taskManager = new Thread(this::manageTasks);
    private final ArrayList<NetTask> tasks = new ArrayList<>();
    private final Stack<Packet> packetStack = new Stack<>();

    private BiHashMap<Class<? extends RequestPacket>, Class<? extends ResponsePacket>> packetPairs = new BiHashMap<>();
    private HashMap<Class<? extends RequestPacket>, PacketProvider<? super RequestPacket, ? extends ResponsePacket>> providers = new HashMap<>();
    private final HashMap<Class<? extends Packet>, BiConsumer<Network, ? super Packet>> notificationHandlers = new HashMap<>();

    /* streaming */
    private HashMap<RequestPacket<?>, StreamHandler<?>> downstreams = new HashMap<>();
    private ArrayList<Upstreamer> upstreams = new ArrayList<>();

    /* callbacks */
    private BiConsumer<Network, Remote> onConnect;
    private BiConsumer<Network, Remote> onDisconnect;

    public Network() {
        this(1024);
    }

    public Network(int udpBufferSize) {
        this.udpBufferSize = udpBufferSize;
    }

    /**
     * Starts up a TCP server given a port.
     *
     * @param localTcpPort local tcp port
     * @param localUdpPort local udp port
     * @param beaconPort local udp port for discovery beacon
     * @throws IOException
     */
    public void startServer(int localTcpPort, int localUdpPort, int beaconPort) throws IOException {
        if (running) throw new IllegalStateException("Network has already started.");
        serverSocket = new ServerSocket(localTcpPort);
        udpSocket = new UdpSocket(localUdpPort, udpBufferSize);
        logf("Started server on ports %d:%d.%n", localTcpPort, localUdpPort);
        // start up client listening thread
        serverListener = new Thread(this::listen);
        serverListener.start();
        // start up beacon
        beaconSocket = new DatagramSocket(beaconPort);
        beacon = new Thread(this::beacon);
        beacon.start();

        localAddress = new RemoteAddress(udpSocket.getLocalAddress(),
                serverSocket.getLocalPort(), udpSocket.getLocalPort());
        server = true;
        startNetwork();
        running = true;
    }

    /**
     * Starts up a TCP client and connects to a server
     * given the remote address and port.
     *
     * @param remoteAddress remote address
     * @throws IOException
     */
    public void startClient(RemoteAddress remoteAddress) throws IOException {
        if (running) throw new IllegalStateException("Network has already started.");
        udpSocket = new UdpSocket(0, udpBufferSize);
        remoteServer = connect(remoteAddress, udpSocket.getLocalPort());

        localAddress = new RemoteAddress(udpSocket.getLocalAddress(),
                remoteServer.getTcpSocket().getLocalPort(), udpSocket.getLocalPort());
        server = false;
        startNetwork();
        running = true;
    }

    public void startPeer(RemoteAddress remoteAddress, int localTcpPort, int localUdpPort, int broadcastPort) throws IOException {
        if (running) throw new IllegalStateException("Network has already started.");
        remotes.add(connect(remoteAddress, localUdpPort));
        // start as server role
        startServer(localTcpPort, localUdpPort, broadcastPort);
    }

    public Remote connect(RemoteAddress remoteAddress, int localUdpPort) throws IOException {
        Socket socket = new Socket(remoteAddress.getAddress(), remoteAddress.getTcpPort());
        DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
        // send udp port over
        outputStream.writeInt(localUdpPort);
        outputStream.flush();
        Remote remote =  new Remote(socket, remoteAddress.getUdpPort());
        if (onConnect != null) onConnect.accept(this, remote);
        return remote;
    }

    /**
     * Common start for the network.
     */
    private void startNetwork() {
        taskManager.start();
    }

    public RemoteAddress[] findBeacons(int beaconPort, int timeout) {
        ArrayList<RemoteAddress> servers = new ArrayList<>();
        DatagramSocket socket;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
        } catch (SocketException e) {
            e.printStackTrace();
            return new RemoteAddress[0];
        }

        // spawn a thread for blocking method invokes
        Thread thread = new Thread(() -> {
            DatagramPacket inputPacket = new DatagramPacket(new byte[16], 16);
            DatagramPacket outputPacket = new DatagramPacket(new byte[0], 0);
            ByteArrayInputStream arrayInput = new ByteArrayInputStream(inputPacket.getData());
            DataInputStream input = new DataInputStream(arrayInput);

            try {
                outputPacket.setAddress(Inet4Address.getByName("255.255.255.255"));
                outputPacket.setPort(beaconPort);
                // broadcast packets to everyone. (almost everyone because of general address)
                socket.send(outputPacket);
            } catch (IOException e) {
                e.printStackTrace();
            }

            log("Searching for beacons...");
            while (!socket.isClosed()) {
                // wait for responses
                try {
                    socket.receive(inputPacket);
                    int tcpPort = input.readInt();
                    int udpPort = input.readInt();
                    logf("Found beacon at %s:%d:%d. %n", inputPacket.getAddress(), tcpPort, udpPort);
                    servers.add(new RemoteAddress(inputPacket.getAddress(), tcpPort, udpPort));
                } catch (IOException e) {
                    if (!socket.isClosed()) e.printStackTrace();
                }

            }
        });
        thread.start();

        try {
            Thread.sleep(timeout);
            // close socket, interrupting the thread
            socket.close();
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return servers.toArray(new RemoteAddress[0]);
    }

    /**
     * Listens for client broadcasts through UDP. (server only)
     */
    private void beacon() {
        logf("Started beacon on port %d.%n", beaconSocket.getLocalPort());
        DatagramPacket inputPacket = new DatagramPacket(new byte[0], 0);
        DatagramPacket outputPacket = new DatagramPacket(new byte[16], 16);
        ByteArrayOutputStream arrayOutput = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(arrayOutput);
        try {
            // write tcp port
            output.writeInt(serverSocket.getLocalPort());
            // write udp port
            output.writeInt(udpSocket.getLocalPort());
            output.flush();
            outputPacket.setData(arrayOutput.toByteArray());
            while (!beaconSocket.isClosed()) {
                try {
                    // receive whatever
                    beaconSocket.receive(inputPacket);
                    // send back tcp port
                    outputPacket.setSocketAddress(inputPacket.getSocketAddress());
                    beaconSocket.send(outputPacket);
                } catch (IOException e) {
                    if (!beaconSocket.isClosed()) e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            output.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        log("Closed beacon.");
    }

    /**
     * Listens for incoming client connections. (server only)
     */
    private void listen() {
        log("Started client listener.");
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                // wait for udp port
                DataInputStream inputStream = new DataInputStream(socket.getInputStream());
                int udpPort = inputStream.readInt();
                Remote remote = new Remote(socket, udpPort);
                synchronized (remotes) {
                    remotes.add(remote);
                }
                logf("Client connected: %s.%n", remote);
                if (onConnect != null) onConnect.accept(this, remote);
            } catch (SocketException e) {
                if (!serverSocket.isClosed()) e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        log("Closed client listener.");
    }

    /**
     * Handles fresh packets as they are received by
     * remotes.
     *
     * @param remote remote
     * @param packet packet
     */
    private void receive(Remote remote, Packet packet) {
        // logf("Received packet from %s.%n", remote);
        if (packet.getTransactionId() == 0) {
            // notification packet
            onNotification(packet);
            return;
        }

        synchronized (packetStack) {
            packetStack.push(packet);
            // check with providers
            if (packet instanceof RequestPacket) {
                RequestPacket requestPacket = (RequestPacket) packet;
                if (requestPacket.getStreamInterval() != 0) {
                    // fulfill stream request
                    for (Upstreamer upstreamer : upstreams) {
                        // check for duplicate streams
                        if (upstreamer.getRequestPacket().getClass().isInstance(requestPacket)) {
                            if (upstreamer.hasRemote(remote)) {
                                if (requestPacket.getStreamInterval() > 0) {
                                    // requester already has a stream of the same class
                                    if (upstreamer.getInterval() != requestPacket.getStreamInterval()) {
                                        // remove remote from old stream and make a new one
                                        upstreamer.removeRemote(remote);
                                        // break out of for loop to create stream
                                        break;
                                    } else {
                                        // just update the upstream remote
                                        upstreamer.addRemote(remote, requestPacket);
                                        packetStack.pop();
                                        return;
                                    }
                                } else {
                                    // remove the stream
                                    upstreamer.removeRemote(remote);
                                    packetStack.pop();
                                    return;
                                }
                            }
                        }
                        // share stream if possible
                        if (upstreamer.canShareWith(requestPacket)) {
                            upstreamer.addRemote(remote, requestPacket);
                            logf("Sharing a '%s' stream.%n", packetPairs.getB(requestPacket.getClass()).getSimpleName());
                            packetStack.pop();
                            return;
                        }
                    }

                    if (requestPacket.getStreamInterval() > 0) {
                        // otherwise start up a new upstream
                        if (providers.containsKey(requestPacket.getClass())) {
                            Upstreamer upstreamer = new Upstreamer(this, requestPacket, remote);
                            upstreams.add(upstreamer);
                            executeTask(upstreamer);
                            logf("Created a new '%s' stream.%n", packetPairs.getB(requestPacket.getClass()).getSimpleName());
                        } else {
                            errorf("Cannot create upstream: no provider for '%s'.%n", requestPacket.getClass().getCanonicalName());
                        }
                    } else {
                        logf("No specified stream to remove as requested: %s.%n", remote);
                    }
                    packetStack.pop();
                    return;
                } else if (providers.containsKey(requestPacket.getClass())) {
                    // fulfill simple request
                    ResponsePacket responsePacket = providers.get(requestPacket.getClass()).provide(this, requestPacket);
                    responsePacket.setTransactionId(requestPacket.getTransactionId());
                    remote.send(responsePacket);
                    logf("Sent a '%s' to fulfill request.%n", packetPairs.getB(requestPacket.getClass()).getSimpleName());
                    packetStack.pop();
                    return;
                }
            } else if (packet instanceof ResponsePacket) {
                ResponsePacket responsePacket = (ResponsePacket) packet;
                // check downstreams
                for (RequestPacket<?> requestPacket : downstreams.keySet()) {
                    if (validateResponse(requestPacket, responsePacket)) {
                        // feed stream with certainty from type checking
                        feedStream(downstreams.get(requestPacket), responsePacket);
                        packetStack.pop();
                        return;
                    }
                }
                // notify threads listening for responses
                packetStack.notifyAll();
            } else {
                // normal notify packet
                onNotification(packet);
                packetStack.pop();
                return;
            }
        }
    }

    private void onNotification(Packet packet) {
        synchronized (notificationHandlers) {
            for (Class<? extends Packet> clazz : notificationHandlers.keySet()) {
                if (clazz.isInstance(packet)) {
                    notificationHandlers.get(clazz).accept(this, packet);
                }
            }
        }
    }

    /**
     * Invoked by remotes after a disconnect.
     *
     * @param remote remote
     */
    private void onRemoteDisconnect(Remote remote) {
        if (server) {
            synchronized (remotes) {
                remotes.remove(remote);
            }
        }
        // remove from streams
        for (Upstreamer upstreamer : upstreams) {
            upstreamer.removeRemote(remote);
        }
        if (onDisconnect != null) onDisconnect.accept(this, remote);
    }

    private static int TRANSACTION_COUNT = 0;

    /**
     * Stamps request packets with a unique transaction
     * id. (Just a increments a count)
     *
     * @param requestPacket request packet
     */
    private void stampRequestPacket(RequestPacket requestPacket) {
        requestPacket.setTransactionId(++TRANSACTION_COUNT);
    }

    /**
     * Helper method for safely feeding streams response packets.
     *
     * @param streamHandler stream handler
     * @param packet response packet
     * @param <T>
     */
    @SuppressWarnings("unchecked")
    private <T> void feedStream(StreamHandler<T> streamHandler, ResponsePacket packet) {
        streamHandler.stream((T) packet);
    }

    /**
     * Executes a given task on its own thread.
     *
     * @param task task
     */
    public void executeTask(NetTask task) {
        task.start();
        tasks.add(task);
    }

    /**
     * Manages the lives of tasks (including {@link Upstreamer} instances).
     * Removes them as they die and invokes successors.
     */
    private void manageTasks() {
        log("Started task manager.");
        while (!Thread.currentThread().isInterrupted()) {
            for (int i = 0; i < tasks.size(); i++) {
                NetTask task = tasks.get(i);
                if (!task.isAlive()) {
                    // run child if possible
                    if (task.getNextTask() != null) {
                        executeTask(task.getNextTask());
                    }
                    // remove from lists
                    tasks.remove(i);
                    if (task instanceof Upstreamer) {
                        upstreams.remove(task);
                    }
                    i--;
                }
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
        }
        log("Closed task manager.");
    }

    /**
     * Requests a stream from a given remote. Response packets
     * will be sent at a set and specified interval and given
     * to a {@link StreamHandler}.
     *
     * @param remote to send request
     * @param request of wanted response
     * @param streamHandler handles as packets are received
     * @param interval in milliseconds that the remote should send responses
     * @param <T>
     */
    public <T extends ResponsePacket> void requestStream(Remote remote, RequestPacket<T> request, StreamHandler<T> streamHandler, long interval) {
        request.setStreamInterval(interval);
        remote.send(request);
        downstreams.put(request, streamHandler);
    }

    /**
     * Shuts down a previously requested stream.
     *
     * @param remote to shutdown stream
     * @param request of wanted response
     */
    public void shutdownStream(Remote remote, RequestPacket request) {
        request.setStreamInterval(-1);
        remote.send(request);
        // do stupid, convoluted trash step to remove downstream
        RequestPacket oldStream = null;
        for (RequestPacket packet : downstreams.keySet()) {
            if (packet.getClass().isInstance(request)) {
                oldStream = packet;
            }
        }
        if (oldStream != null) downstreams.remove(oldStream);
    }

    /**
     * Requests a response from a given remote. This is a blocking
     * method, so a free thread may be preferable.
     *
     * @param remote to send request
     * @param request of wanted response
     * @param timeout max time allowed to block (set to 0 for no limit)
     * @param <T>
     * @return wanted response packet (or null if timeout)
     */
    @SuppressWarnings("unchecked")
    public <T extends ResponsePacket> T request(Remote remote, RequestPacket<T> request, long timeout) {
        request.setStreamInterval(0);
        // send request
        remote.send(request);
        // get response
        long elapse = 0;
        do {
            synchronized (packetStack) {
                for (int i = 0; i < packetStack.size(); i++) {
                    Packet packet = packetStack.elementAt(i);
                    if (validateResponse(request, packet)) {
                        packetStack.remove(i);
                        // perform our totally safe cast
                        return (T) packet;
                    }
                }
                long startTime = System.currentTimeMillis();
                try {
                    // wait for changes to packet stack, without spurious wake up protection (:<
                    packetStack.wait(timeout - elapse);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                elapse += System.currentTimeMillis() - startTime;
            }
        } while (timeout == 0 || elapse < timeout);

        return null;
    }

    /**
     * Sends a notification packet to a given remote. Packet must not
     * be of class {@link RequestPacket} or any subclass. Doing so will result
     * in an actual request to the remote and a lost response.
     *
     * @param remote destination
     * @param packet notification
     */
    public void notify(Remote remote, Packet packet) {
        packet.setTransactionId(0);
        remote.send(packet);
    }

    /**
     * Sets a notification callback for a given class. It shall be invoked
     * anytime a notification packet is sent of the said class.
     *
     * @param clazz of packets to handle
     * @param handler handler
     * @param <T> packet class
     */
    public <T extends Packet> void setNotificationCallback(Class<T> clazz, BiConsumer<Network, ? super Packet> handler) {
        synchronized (notificationHandlers) {
            notificationHandlers.put(clazz, handler);
        }
    }

    /**
     * Sets a notification callback for all packets. It shall be invoked
     * anytime a notification packet is sent.
     *
     * @param handler handler
     */
    public void setNotificationCallback(BiConsumer<Network, ? super Packet> handler) {
        setNotificationCallback(Packet.class, handler);
    }

    /**
     * Registers a request-response pair for packet validations. All pairs should be
     * registered.
     *
     * @param request request class
     * @param response response class
     * @param <T> response class
     */
    public <T extends ResponsePacket> void registerPacketPair(Class<? extends RequestPacket<T>> request, Class<T> response) {
        packetPairs.put(request, response);
    }

    /**
     * Adds a provider, which is polled when a remote requests information
     * from you. It "provides" the proper response packets with regards to
     * a given request packet.
     *
     * @param clazz of requests to provide for
     * @param provider something that will "provide" responses
     * @param <T>
     */
    public <T extends RequestPacket> void addProvider(Class<T> clazz, PacketProvider<? super RequestPacket, ? extends ResponsePacket<T>> provider) {
        providers.put(clazz, provider);
    }

    /**
     * Validates responses with their associated request packets.
     *
     * @param request request
     * @param response response
     * @param <T> response class
     * @return success
     */
    private <T extends ResponsePacket> boolean validateResponse(RequestPacket<T> request, Packet response) {
        if (packetPairs.containsA(request.getClass())) {
            if (packetPairs.getB(request.getClass()).isInstance(response)) {
                ResponsePacket responsePacket = (ResponsePacket) response;
                if (request.getTransactionId() == responsePacket.getTransactionId()) {
                    return true;
                }
            }
        } else {
            // unregistered request class
            errorf("Unregistered request packet: %s.%n", request.getClass().getCanonicalName());
        }
        return false;
    }

    /**
     * Stops everything. Does a small amount of
     * blocking, bear with me. /:
     */
    public void stop() {
        if (!running) return;

        log("Stopping network...");
        try {
            // stop tasks
            for (NetTask task : tasks) {
                task.interrupt();
                try {
                    task.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            // stop task manager
            taskManager.interrupt();
            taskManager.join();

            // stop udp socket (blocks)
            udpSocket.close();

            if (server) {
                // stop server threads by closing socket
                serverSocket.close();
                serverListener.join();
                beaconSocket.close();
                beacon.join();
                // close remotes
                for (Remote remote : remotes) remote.close();
            } else {
                // close server remote
                remoteServer.close();
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return;
        }

        running = false;
        log("Network has been stopped.");
    }

    /**
     * Handles UDP operations. It is a single socket, hence the "connectionless" protocol.
     */
    public class UdpSocket {
        private DatagramSocket socket;
        private ObjectOutputStream output;
        private ObjectInputStream input;
        private ByteArrayInputStream arrayInput;
        private ByteArrayOutputStream arrayOutput;
        private DatagramPacket inputPacket;
        private DatagramPacket outputPacket;
        private Thread listener;

        private UdpSocket(int localUdpPort, int bufferSize) throws IOException {
            socket = new DatagramSocket(localUdpPort);
            inputPacket = new DatagramPacket(new byte[bufferSize], bufferSize);
            outputPacket = new DatagramPacket(new byte[0], 0);
            arrayOutput = new ByteArrayOutputStream(bufferSize);
            output = new ObjectOutputStream(arrayOutput);
            // discard the header sent by object output stream
            output.flush();
            arrayOutput.reset();

            // stream header transaction makes things too complicated, fool it. (little hack)
            inputPacket.getData()[0] = (byte) (ObjectStreamConstants.STREAM_MAGIC >>> 8);
            inputPacket.getData()[1] = (byte) (ObjectStreamConstants.STREAM_MAGIC);
            inputPacket.getData()[2] = (byte) (ObjectStreamConstants.STREAM_VERSION >>> 8);
            inputPacket.getData()[3] = (byte) (ObjectStreamConstants.STREAM_VERSION);
            arrayInput = new ByteArrayInputStream(inputPacket.getData());
            input = new ObjectInputStream(arrayInput);

            listener = new Thread(this::listen);
            listener.start();
        }

        /**
         * Send packets via UDP socket
         * @param packet packet to send
         * @param socketAddress destination
         */
        private synchronized void send(Packet packet, InetSocketAddress socketAddress) {
            try {
                arrayOutput.reset();
                output.writeObject(packet);
                output.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
            outputPacket.setSocketAddress(socketAddress);
            outputPacket.setData(arrayOutput.toByteArray());
            try {
                socket.send(outputPacket);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /**
         * Thread for listening to incoming packets.
         */
        private void listen() {
            logf("Listening for incoming UDP packets on port %d...%n", socket.getLocalPort());

            while (!socket.isClosed()) {
                // receive data
                try {

                    arrayInput.reset();
                    socket.receive(inputPacket);

                    // parse sender
                    Remote remote = null;
                    InetSocketAddress sender = (InetSocketAddress) inputPacket.getSocketAddress();
                    if (server) {
                        for (Remote r : remotes) {
                            if (r.validateUdpAddress(sender)) {
                                remote = r;
                            }
                        }
                    } else {
                        if (remoteServer.validateUdpAddress(sender)) {
                            remote = remoteServer;
                        }
                    }

                    if (remote != null) {
                        // parse packet
                        Object obj = input.readObject();
                        if (obj instanceof Packet) {
                            // feed to common network parser
                            Network.this.receive(remote, (Packet) obj);
                        } else {
                            // weird ass object
                        }
                    } else {
                        // unknown sender, wtf?
                    }
                } catch (IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }

        }

        public int getLocalPort() {
            return socket.getLocalPort();
        }

        public InetAddress getLocalAddress() {
            return socket.getLocalAddress();
        }

        private void close() throws InterruptedException {
            socket.close();
            listener.join();
        }

    }

    /**
     * Class for encapsulating a remote socket and its data streams.
     */
    public class Remote {
        private Socket tcpSocket;
        private InetSocketAddress udpAddress;
        private RemoteAddress address;
        private ObjectInputStream tcpInput;
        private ObjectOutputStream tcpOutput;

        private Thread listener;

        private Remote(Socket tcpSocket, int udpPort) throws IOException {
            this.tcpSocket = tcpSocket;
            udpAddress = new InetSocketAddress(tcpSocket.getInetAddress(), udpPort);
            address = new RemoteAddress(tcpSocket.getInetAddress(), tcpSocket.getPort(), udpPort);
            tcpOutput = new ObjectOutputStream(tcpSocket.getOutputStream());
            tcpOutput.flush();
            tcpInput = new ObjectInputStream(tcpSocket.getInputStream());

            listener = new Thread(this::listen);
            listener.start();
        }

        /**
         * Sends packets over the network.
         *
         * @param packet
         */
        private synchronized void send(Packet packet) {
            if (packet == null) {
                error("Cannot send a null object.");
                return;
            }

            if (packet instanceof RequestPacket) {
                // stamp request packet
                stampRequestPacket((RequestPacket) packet);
            }

            if (packet.getProtocol() == TCP_PROTOCOL) {
                try {
                    tcpOutput.writeObject(packet);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (packet.getProtocol() == UDP_PROTOCOL) {
                // forward to udp socket
                udpSocket.send(packet, udpAddress);
            }
        }

        /**
         * Listens for incoming packets.
         */
        private void listen() {
            logf("Listening for incoming TCP packets from %s:%d...%n",
                    tcpSocket.getInetAddress().getHostAddress(), tcpSocket.getPort());
            while (!tcpSocket.isClosed()) {
                Packet packet = receive();
                if (packet != null) Network.this.receive(this, packet);
            }

            // clean yourself up
            onRemoteDisconnect(this);
            logf("Connection to %s:%d closed.%n",
                    tcpSocket.getInetAddress().getHostAddress(), tcpSocket.getPort());
        }

        /**
         * Blocks until a packet is received or something
         * wheely bad happens.
         *
         * @return the new packet
         */
        private Packet receive() {
            try {
                Object object = tcpInput.readObject();
                // throw away non-packets
                if (object instanceof Packet) {
                    return (Packet) object;
                } else return null;
            } catch (EOFException e) {
                try {
                    // there's nothing left to read, close the socket
                    tcpSocket.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            } catch (SocketException e) {
                if (!tcpSocket.isClosed()) e.printStackTrace();
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
            return null;
        }

        private Socket getTcpSocket() {
            return tcpSocket;
        }

        public int available() {
            try {
                return tcpInput.available();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return 0;
        }

        public boolean validateUdpAddress(InetSocketAddress udpAddress) {
            return this.udpAddress.equals(udpAddress);
        }

        /**
         * Closes the remote socket and its streams.
         *
         * @throws IOException
         */
        public void close() throws IOException {
            // this will freak out the thread and kill it
            tcpSocket.close();
        }

        public String toString() {
            return tcpSocket.getInetAddress().getHostAddress() + ":" + tcpSocket.getPort() + ":" + udpAddress.getPort();
        }

        public RemoteAddress getAddress() {
            return address;
        }
    }

    public HashMap<Class<? extends RequestPacket>, PacketProvider<? super RequestPacket, ? extends ResponsePacket>> getProviders() {
        return providers;
    }

    public void setConnectCallback(BiConsumer<Network, Remote> onConnect) {
        this.onConnect = onConnect;
    }

    public void setDisconnectCallback(BiConsumer<Network, Remote> onDisconnect) {
        this.onDisconnect = onDisconnect;
    }

    public Remote getRemoteServer() {
        return remoteServer;
    }

    /**
     * Applies a given action to each existing remote. The remote
     * array list cannot simply be given to user, since it is used
     * in many threads and should be synchronized before use.
     *
     * @param action to apply
     */
    public void forEachRemote(BiConsumer<Network, Remote> action) {
        synchronized (remotes) {
            for (Remote remote : remotes) action.accept(this, remote);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isServer() {
        return server;
    }

    public RemoteAddress getLocalAddress() {
        return localAddress;
    }

    private void log(String string) {
        System.out.println("[SuperJNet]: " + string);
    }

    private void logf(String format, Object ... args) {
        System.out.printf("[SuperJNet]: " + format, args);
    }

    private static final int HEX_WIDTH = 30;
    private void log(byte[] data) {
        int max = ((data.length / HEX_WIDTH) + 1) * HEX_WIDTH;
        for (int i = 0; i <= max; i++) {
            if (i > 0 && i % HEX_WIDTH == 0) {
                System.out.print("  ");
                for (int j = i - HEX_WIDTH; j < i; j++) {
                    if (j < data.length) System.out.printf("%C ", (char) data[j]);
                }
                System.out.println();
            }
            if (i < data.length) {
                System.out.printf("%02X ", data[i]);
            } else System.out.print("   ");
        }
        System.out.println();
    }

    private void error(String string) {
        System.err.println("[SuperJNet]: " + string);
    }

    private void errorf(String format, Object ... args) {
        System.err.printf("[SuperJNet]: " + format, args);
    }

    /**
     * For handling downstream responses.
     *
     * @param <T>
     */
    @FunctionalInterface
    public interface StreamHandler<T> {
        void stream(T response);
    }

    /**
     * For automatically "providing" responses to remote
     * requests.
     *
     * @param <A> request packet
     * @param <B> response packet
     */
    @FunctionalInterface
    public interface PacketProvider<A, B extends ResponsePacket> {
        B provide(Network network, A a);
    }

    /**
     * Sends responses at a fixed rate as a stream. Can be shared
     * between multiple remotes.
     */
    public class Upstreamer extends NetTask {
        private RequestPacket requestPacket;
        private long interval;

        /* Remote -> Transaction ID Map */
        private final HashMap<Remote, Integer> remotes = new HashMap<>();

        Upstreamer(Network network, RequestPacket requestPacket, Remote remote) {
            super(network, null, null);
            this.requestPacket = requestPacket;
            interval = requestPacket.getStreamInterval();
            setBody(this::loop);

            remotes.put(remote, requestPacket.getTransactionId());
        }

        /**
         * Main loop.
         *
         * @param network network
         */
        private void loop(Network network) {
            logf("[%s]: Started upstream for:%n", this);
            for (Remote remote : remotes.keySet())
                logf("[%s]:    %s.%n", this, remote);
            while (!isInterrupted()) {
                ResponsePacket responsePacket = providers.get(requestPacket.getClass()).provide(network, requestPacket);
                synchronized (remotes) {
                    for (Remote remote : remotes.keySet()) {
                        // tag response with transaction id
                        responsePacket.setTransactionId(remotes.get(remote));
                        // send to remote
                        remote.send(responsePacket);
                    }
                }
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        /**
         * Adds / updates a remote with a request packet (for its transaction
         * id).
         *
         * @param remote remote
         * @param requestPacket request packet
         */
        public void addRemote(Remote remote, RequestPacket requestPacket) {
            logf("[%s]: Added new remote: %s.%n", this, remote);
            synchronized (remotes) {
                remotes.put(remote, requestPacket.getTransactionId());
                logf("[%s]: Updated upstream roster:%n", this);
                for (Remote r : remotes.keySet())
                    logf("[%s]:    %s.%n", this, r);
            }
        }

        /**
         * Removes a remote from the roster if it exists.
         *
         * @param remote remote
         */
        public void removeRemote(Remote remote) {
            synchronized (remotes) {
                remotes.remove(remote);
                if (remotes.isEmpty()) {
                    interrupt();
                }
                logf("[%s]: Removed remote: %s.%n", this, remote);
                logf("[%s]: Updated upstream roster:%n", this);
                for (Remote r : remotes.keySet())
                    logf("[%s]:    %s.%n", this, r);
            }
        }

        /**
         * Checks if the roster contains the remote.
         *
         * @param remote remote
         * @return exists?
         */
        public boolean hasRemote(Remote remote) {
            synchronized (remotes) {
                return remotes.containsKey(remote);
            }
        }

        /**
         * Checks if stream can be shared with the given
         * conditions. It must be the same response packet class and
         * same stream interval.
         *
         * @param requestPacket request packet
         * @return can share?
         */
        public boolean canShareWith(RequestPacket requestPacket) {
            return this.requestPacket.canShareStream(requestPacket);
        }

        public String toString() {
            return String.format("Upstream (class: '%s', interval: %d ms)",
                    packetPairs.getB(requestPacket.getClass()).getSimpleName(), interval);
        }

        public RequestPacket getRequestPacket() {
            return requestPacket;
        }

        public HashMap<Remote, Integer> getRemotes() {
            return remotes;
        }

        public long getInterval() {
            return interval;
        }
    }


    public static final int TCP_PROTOCOL = 0;
    public static final int UDP_PROTOCOL = 1;
    public static class Packet implements Serializable {
        private transient int protocol; /* don't need this going over the network */
        private int transactionId;

        public Packet(int protocol) {
            this.protocol = protocol;
        }

        public Packet() {
            protocol = TCP_PROTOCOL;
        }

        int getTransactionId() {
            return transactionId;
        }

        void setTransactionId(int transactionId) {
            this.transactionId = transactionId;
        }

        public int getProtocol() {
            return protocol;
        }

        public void setProtocol(int protocol) {
            this.protocol = protocol;
        }
    }

    public static class RemoteAddress {
        private InetAddress address;
        private int tcpPort;
        private int udpPort;

        public RemoteAddress(InetAddress address, int commonPort) {
            this.address = address;
            this.tcpPort = commonPort;
            this.udpPort = commonPort;
        }

        public RemoteAddress(InetAddress address, int tcpPort, int udpPort) {
            this.address = address;
            this.tcpPort = tcpPort;
            this.udpPort = udpPort;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof RemoteAddress) {
                RemoteAddress ra = (RemoteAddress) obj;
                return ra.getAddress().getHostAddress().equals(address.getHostAddress()) &&
                        ra.getTcpPort() == tcpPort &&
                        ra.getUdpPort() == udpPort;
            } else return false;
        }

        public InetAddress getAddress() {
            return address;
        }

        public int getTcpPort() {
            return tcpPort;
        }

        public int getUdpPort() {
            return udpPort;
        }
    }
}
