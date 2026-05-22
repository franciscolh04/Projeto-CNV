package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.concurrent.atomic.AtomicInteger;

public class WorkerNode {
    private final String instanceId;
    private final String ip;
    private final AtomicInteger work;
    private final AtomicInteger missedPings;

    public WorkerNode(String instanceId, String ip) {
        this.instanceId = instanceId;
        this.ip = ip;
        this.work = new AtomicInteger(0);
        this.missedPings = new AtomicInteger(0);
    }

    public String getInstanceId() { return instanceId; }
    public String getIp() { return ip; }
    public AtomicInteger getWork() { return work; }
    public int getMissedPings() { return this.missedPings.get(); }
    public void setMissedPings(int value) { this.missedPings.set(value); }
    public void incrementWork(int value) { this.work.addAndGet(value); }
}