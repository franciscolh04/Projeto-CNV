package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Metrics poller thread - collects metrics
 */
public class MSSPoller implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(MSSPoller.class.getName());

    @Override
    public void run() {
        // TODO:
        // Read the last X records of all operations and map their characteristics 
        /*
        fractals: generates a fractal image, based on image width, height, and number of iterations of the Julia
            function.
        grayscott: simulates Gray-Scott-based interaction of chemicals, based on region size, max iterations,
            substance feed and kill rates, stopping condition on extinction, and seed mode (original distribution).
        dna: a DNA Genome Matcher highlighting DNA matches, based on two FASTA sequences, minimum
            match length, and stopping condition on the first match. */
        // For each record, we have the parameters that were used and the actual cost measured by Javassist (instruction_count).

        // the predicted cost can be an assignment to each pixel (fractals), to each iteration (grayscott) or to each comparison (dna).
        // Javassist gives us the total cost, but we need to divide it by the number of work units (pixels, iterations, comparisons) to have a cost per work unit.
        // Then, we can take an average of the cost per work unit for each type of operation (fractals, grayscott, dna) and use that as our prediction for the next request.

        // 3. Update the model in the cache (Thread-Safe)
        LoadBalancer.metricsModelCache.put("operation", 0);
    }

}
