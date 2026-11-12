package dev.logast.cli;

import dev.logast.IncrementalScanner;
import dev.logast.Logast;
import dev.logast.policy.Policy;
import dev.logast.review.ReviewServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "review", description = "Open a local page to write the NEEDS TEXT messages, dry-run and apply them (for humans)")
final class Review extends Main.Common implements Callable<Integer> {
    @Option(names = "--port", defaultValue = "0", description = "Port (0 = any free port)")
    int port;
    @Option(names = "--open", description = "Open the page in the default browser")
    boolean open;

    @Override
    public Integer call() throws Exception {
        Policy policy = policy();
        List<Path> cp = classpath();
        ReviewServer server = new ReviewServer(root(), () -> new IncrementalScanner(cp, r -> policy), policy);
        String url = server.start(port);
        System.out.println("logast review: " + url + "  (local only; Ctrl+C to stop)");
        if (open) {
            new ProcessBuilder("xdg-open", url).start();
        }
        Thread.currentThread().join();
        return 0;
    }
}
