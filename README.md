# SuperJNet
Very cool request and response TCP/UDP Java network manager with object serialization

## Getting Started
So, you wanna use Java sockets with object serialization, but have come to realize that the code gets messy by nature. It begins to overwhelm you with tiny, obscure problems that no one would have thought to exist, and so after toiling away in madness, you've reached the promised lands right here on this page. Include this library in your project and lean back (unless your chair doesn't have a back, in this case lean forward).

###  Starting a Server
```java
Network network = new Network();
// start a server on TCP port 55555, UDP port 55556, beacon port 22222
network.startServer(55555, 55556, 22222);
```

### Starting a Client
```java
Network network = new Network();
// start a client and connect to "localhost" at TCP port 55555, UDP port 55556
network.startClient(new Network.RemoteAddress(InetAddress.getByName("localhost"), 55555, 55556));
```
And that's that. Not super cool I know, so lets try doing some requests and responses.

### Requests and Responses
You connect to a server and would like to know its name, so you send a request for its name and the server responds with its name given at birth. Let's begin by creating the request and response packets.

```java
/* in shared network code */

public class RequestName extends RequestPacket<RespondName> {
  private static final long serialVersionUID = ...; // see "Serial Version UID for getting this
  
}

public class RespondName extends ResponsePacket<RequestName> {
  private static final long serialVersionUID = ...; // see "Serial Version UID for getting this
  public String name;
}
```
Notice when the packets extend their corresponding packet types (RequestPacket and ResponsePacket), the class of the associated response or request are within the brackets. This is important as it allows the two types to be bounded, providing some nice, strong type-checking. However, they need to binded once more in code for safe credibility checking when responses are received. So lets:

```java
/* also in shared network code */

network.registerPacketPair(RequestName.class, RespondName.class);
```
Done and done. Now what happens when we receive a request for something? We don't panic, we ask the manager to invoke a callback method to "provide" a response. Let's just call them "providers."

```java
/* in server code */
public RespondName provideName(Network network, RequestName request) {
  RespondName response = new RespondName();
  response.name = "Alex";
  return response;
}

public void setupNetwork() {
  // set the provider for 'RequestName' requests
  network.setProvider(RequestName.class, this::provideName); // look up method references if this looks weird
}
```
Alrighty, we are set to request that packet. Let's go:
```java
/* in client code */
// request that name from the server. Allow for a 1000 ms block at most.
RespondName response = network.request(network.getRemoteServer(), new RequestName(), 1000);
if (response != null) System.out.printf("His name is %s!!!%n", response.name);
```
Hopefully, you got a nice message reading "His name is Alex!!!." If not, I'm so sorry. Read the [testing code](https://github.com/anotherAlex154/SuperJNet/blob/master/testing/Main.java) file for guidance.

### Streaming
So, you need a steady stream of response packets, but don't want to submit a request for each one. Lets build a stream:
```java
/* requester's code */
public void onStreamResponse(Network network RespondStuff response) {
  // handle the stream response
}

public void setup() {
  // request a stream of responses at a 1000 ms interval, invoking the onStreamResponse() method
  network.requestStream(remote, new RequestStuff(), this::onStreamResponse, 1000); 
}
```
Pretty sick huh. If your wondering about the how to get "Remote" objects, know that these are in every response packet through the `getSender()` method and are given to callbacks like `network.setConnectCallback(<callback method>)`. Read JavaDocs for more.

### Notifications
There may be times when you just want to be notified with packets instead of having a whole request and response transaction. You may notify a remote through:
```java
/* notifier's code */
network.notify(remote, new Stuff());
```
It may be a good idea to be ready for notifications
```java
/* one to be notified's code */
public void onNotifiedWithStuff(Network network, Stuff stuff) {
  // handle notification
}

public void setup() {
  // set notification callback for the 'Stuff' class
  network.setNotificationCallback(Stuff.class, this::onNotifiedWithStuff);
}
```
Note: for packets like 'Stuff' you may also extend 'Network.Packet' for better readability, since you're not really using the request and response method. Never use 'RequestPacket' to notify. For an explanation why, just read the code. 

### Cleanup
Always stop the network when you're done.
```java
network.stop();
```

### Serial Version UID
Use the `serialver` commandline tool to generate this.
