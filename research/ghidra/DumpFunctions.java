// Offline research helper. Not compiled or packaged by Maven; native SDKs remain reference-only.
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class DumpFunctions extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        Path output = Path.of(args[0]);
        Pattern seeds = Pattern.compile(args[1]);
        int depth = Integer.parseInt(args[2]);
        Set<Function> selected = new LinkedHashSet<>();
        var queue = new ArrayDeque<Function>();
        var functions = currentProgram.getFunctionManager().getFunctions(true);
        while (functions.hasNext()) {
            Function function = functions.next();
            if (seeds.matcher(function.getName()).find() && !function.isExternal()) {
                selected.add(function);
                queue.add(function);
            }
        }
        for (int level = 0; level < depth; level++) {
            int count = queue.size();
            while (count-- > 0) {
                for (Function callee : queue.removeFirst().getCalledFunctions(monitor)) {
                    if (!callee.isExternal() && !callee.isThunk() && selected.size() < 350
                            && !callee.getName().matches(".*(evbuffer|evhttp|evdns|evtimer|bufferevent|log_write|log_print|pthread|memcpy|memset|calloc|malloc|free).*")) {
                        if (selected.add(callee)) {
                            queue.add(callee);
                        }
                    }
                }
            }
        }
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        var text = new StringBuilder("// Program: " + currentProgram.getName() + "\n");
        try {
            for (Function function : selected) {
                monitor.checkCancelled();
                text.append("\n// FUNCTION ").append(function.getName()).append(" @ ").append(function.getEntryPoint()).append("\n");
                var result = decompiler.decompileFunction(function, 60, monitor);
                if (result.decompileCompleted()) {
                    text.append(result.getDecompiledFunction().getC());
                } else {
                    text.append("// Decompilation failed: ").append(result.getErrorMessage()).append("\n");
                }
            }
        } finally {
            decompiler.dispose();
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, text);
        println("Saved " + selected.size() + " decompiled functions to " + output);
    }
}
