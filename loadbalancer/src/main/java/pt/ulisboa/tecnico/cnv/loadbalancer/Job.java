package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.concurrent.CompletableFuture;
import com.sun.net.httpserver.HttpExchange;

public class Job implements Comparable<Job> {
    public final HttpExchange exchange;
    public final String path;
    public final String query;
    public final int estimatedWork;
    public final CompletableFuture<String> futureResult;
    public final long creationTime;
    public int retries = 0;
    
    public final int MAX_RETRIES = 2;

    public Job(HttpExchange exchange, String path, String query, int estimatedWork) {
        this.exchange = exchange;
        this.path = path;
        this.query = query;
        this.estimatedWork = estimatedWork;
        this.futureResult = new CompletableFuture<>();
        this.creationTime = System.nanoTime();
    }

    @Override
    public int compareTo(Job other) {
        if (this.retries != other.retries) {
            return Integer.compare(other.retries, this.retries);
        }
        return Long.compare(this.creationTime, other.creationTime);
    }
}