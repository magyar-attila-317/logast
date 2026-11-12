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
            String os = System.getProperty("os.name").toLowerCase();
            String[] cmd = os.contains("mac") ? new String[]{"open", url}
                    : os.contains("win") ? new String[]{"rundll32", "url.dll,FileProtocolHandler", url}
                    : new String[]{"xdg-open", url};
            try {
                new ProcessBuilder(cmd).start();
            } catch (java.io.IOException e) {
                System.out.println("could not open a browser (" + cmd[0] + "); open the URL above manually");
            }
        }
        Thread.currentThread().join();
        return 0;
    }
}
