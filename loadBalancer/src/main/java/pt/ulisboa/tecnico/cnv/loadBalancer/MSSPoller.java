package pt.ulisboa.tecnico.cnv.loadBalancer;

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
        // 1. Ler os últimos X registos das operações todas e mapear as suas caracteristicas 
        /*
        fractals: generates a fractal image, based on image width, height, and number of iterations of the Julia
            function.
        grayscott: simulates Gray-Scott-based interaction of chemicals, based on region size, max iterations,
            substance feed and kill rates, stopping condition on extinction, and seed mode (original distribution).
        dna: a DNA Genome Matcher highlighting DNA matches, based on two FASTA sequences, minimum
            match length, and stopping condition on the first match. */
        // Para cada registo, temos os parâmetros que foram usados e o custo real medido pelo Javassist (instruction_count).

        // o custo previsto pode ser uma atribuição a cada pixel (fractals), a cada iteração (grayscott) ou a cada comparação (dna).
        // o javassist dá-nos o custo total, mas precisamos de o dividir pelo número de unidades de trabalho (pixels, iterações, comparações) para termos um custo por unidade de trabalho.
        // Depois, podemos tirar uma média do custo por unidade de trabalho para cada tipo de operação (fractals, grayscott, dna) e usar isso como a nossa previsão para o próximo pedido.

        // 3. Atualizar o modelo na cache (Thread-Safe)
        LoadBalancer.metricsModelCache.put("operation", 0);
    }

}
