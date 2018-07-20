package com.frederiksen.superjnet;

import java.util.function.Consumer;

/**
 * Task that can be executed asynchronously in the
 * network. Can be chained with succeeding tasks, forming
 * a task chain.
 */
public class NetTask extends Thread {
    private Consumer<Network> body;
    private NetTask nextTask;
    private Network network;

    public NetTask(Network network, Consumer<Network> body, NetTask nextTask) {
        this.network = network;
        this.body = body;
        this.nextTask = nextTask;
    }

    @Override
    public void run() {
        body.accept(network);
    }

    public void setBody(Consumer<Network> body) {
        this.body = body;
    }

    public Consumer<Network> getBody() {
        return body;
    }

    public NetTask getNextTask() {
        return nextTask;
    }
}
