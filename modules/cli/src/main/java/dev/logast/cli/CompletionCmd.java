package dev.logast.cli;

import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * #321: {@code logast completion [bash|zsh]}: the shell completion script for every command, option and option value
 * (packs, steps, formats), generated from the commands themselves (picocli's AutoComplete), so it never lags behind.
 * install.sh puts the bash one where bash-completion finds it; zsh loads the same script through bashcompinit.
 */
@Command(name = "completion", description = "Print the shell completion script (bash, zsh): "
        + "source <(logast completion) in ~/.bashrc or ~/.zshrc; install.sh sets up bash")
final class CompletionCmd implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", paramLabel = "SHELL", defaultValue = "bash", description = "bash (default) or zsh")
    String shell;
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        if (!shell.equals("bash") && !shell.equals("zsh")) {
            System.err.println("completion: bash or zsh, not '" + shell + "'");
            return 2;
        }
        String script = AutoComplete.bash("logast", spec.root().commandLine());
        if (shell.equals("zsh")) {
            // the bash script runs in zsh through bashcompinit
            script = "autoload -U +X compinit && compinit\nautoload -U +X bashcompinit && bashcompinit\n" + script;
        }
        System.out.print(script);
        return 0;
    }
}
