import com.frederiksen.superjnet.Network;
import com.frederiksen.superjnet.RequestPacket;
import com.frederiksen.superjnet.ResponsePacket;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;

public class Main {

    public static class RequestVersion extends RequestPacket<RespondVersion> {

    }

    public static class RespondVersion extends ResponsePacket<RequestVersion> {

    }

    public static class RequestWorld extends RequestPacket<RespondWorld> {
        private static final long serialVersionUID = -6605630250094396904L;
    }

    public static class RespondWorld extends ResponsePacket<RequestWorld> {
        private static final long serialVersionUID = -1462008953331936260L;
        private static int COUNT = 0;
        public int count;

        public RespondWorld() {
            count = ++COUNT;
            setProtocol(Network.UDP_PROTOCOL);
        }
    }

    public static class RequestName extends RequestPacket<RespondName> {
        private static final long serialVersionUID = -2564752620355208260L;
    }

    public static class RespondName extends ResponsePacket<RequestName> {
        private static final long serialVersionUID = 5915169529568600354L;
        public String name;

        public RespondName(String name) {
            this.name = name;
        }
    }

    public static boolean IS_SERVER = false;
    public static void main(String[] args) {
        IS_SERVER = args[0].equals("server");

        Network network = new Network();
        network.registerPacketPair(RequestName.class, RespondName.class);
        network.registerPacketPair(RequestWorld.class, RespondWorld.class);

        if (IS_SERVER) {
            network.addProvider(RequestName.class, Main::provider);
            network.addProvider(RequestWorld.class, (net, packet) -> new RespondWorld());

            try {
                network.startServer(55555, 55556, 44444);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            System.out.println("Connecting to server...");
            try {
                network.startClient(new Network.RemoteAddress(InetAddress.getByName("localhost"), 55555, 55556));
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("Requesting name...");
            network.requestStream(network.getRemoteServer(), new RequestWorld(), Main::handleStream, 200);
            //network.requestStream(network.getRemoteServer(), new RequestWorld(), Main::handle2, 500);

            RespondName namePacket = network.request(network.getRemoteServer(), new RequestName(), 10000);
            if (namePacket != null) System.out.printf("Name received: %s.%n", namePacket.name);

            try {
                Thread.sleep(20_000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println("Shutting down stream...");
            network.shutdownStream(network.getRemoteServer(), new RequestWorld());
        }

        System.out.println("Waiting to die...");
        try {
            Thread.sleep(30_000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        network.stop();

    }

    public static RespondName provider(Network network, Object packet) {
        return new RespondName("Alex");
    }

    public static void handleStream(RespondWorld responsePacket) {
        System.out.printf("[Downstream]: Packet %d.%n", responsePacket.count);
    }

    public static void handle2(RespondWorld respondWorld) {
        System.out.printf("[Downstream 2]: Packet %d.%n", respondWorld.count);
    }
}
