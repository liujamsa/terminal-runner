package com.local.shelldeck;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Process;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Device-only smoke tests that do not execute user scripts or kill real processes. */
public final class PtySmokeInstrumentation extends Instrumentation {
    @Override
    public void onStart() {
        Bundle result = new Bundle();
        String testId = "__shelldeck_input_smoke__";
        try {
            InputMemoryStore memory = new InputMemoryStore(getTargetContext());
            String testHash = "a".repeat(64);
            memory.remove(testId);
            require(!memory.isEnabled(testId), "input memory defaults off");
            memory.setEnabled(testId, true);
            memory.append(testId, testHash, "alpha", "First prompt:");
            memory.append(testId, testHash, "", "Press Enter:");
            List<InputMemoryStore.Entry> saved = memory.load(testId, testHash);
            require(saved.size() == 2 && "alpha".equals(saved.get(0).value)
                    && "First prompt:".equals(saved.get(0).prompt)
                    && saved.get(1).value.isEmpty()
                    && "Press Enter:".equals(saved.get(1).prompt),
                    "prompt-aware input memory round trip");
            require(memory.load(testId, "b".repeat(64)).isEmpty(),
                    "input memory is bound to content hash");
            memory.clear(testId);
            require(memory.load(testId, testHash).isEmpty(), "input memory clear");
            memory.remove(testId);

            RootProcessRepository repository = new RootProcessRepository(getTargetContext());
            List<RootProcess> processes = repository.load();
            RootProcess init = find(processes, 1);
            require(init != null && !init.canKill(), "PID 1 must be protected");
            RootProcess self = find(processes, Process.myPid());
            require(self != null && !self.canKill(), "ShellDeck must be protected");
            for (RootProcess process : processes) {
                if ("system_server".equals(process.name) || process.parentPid == 2) {
                    require(!process.canKill(), process.name + " must be protected");
                }
            }
            RootProcessRepository.KillResult blocked = repository.kill(init);
            require(!blocked.success, "PID 1 kill must be intercepted");

            Set<String> scripts = new HashSet<>();
            scripts.add("12345678-1234-1234-1234-123456789abc.sh");
            String sample = "PID PPID USER STAT %CPU RSS NAME ARGS\n"
                    + "345 1 root S 1.2 2048 sh /system/bin/sh "
                    + "/data/user/0/com.local.shelldeck/files/runs/"
                    + "12345678-1234-1234-1234-123456789abc/script.sh\n"
                    + "456 1 u0_a222 S 0.4 4096 com.example.tool com.example.tool\n"
                    + "1 0 root S 0.0 8000 init init second_stage\n";
            List<RootProcess> parsed = RootProcessRepository.parse(sample, scripts);
            require(parsed.get(0).kind == RootProcess.Kind.SCRIPT, "script classification");
            require(parsed.get(1).kind == RootProcess.Kind.APP, "third-party app classification");
            require(!parsed.get(1).canKill(), "third-party app must be read only");
            require(parsed.get(2).kind == RootProcess.Kind.PROTECTED, "core classification");

            result.putString("featureResult", "PASS");
            result.putInt("processCount", processes.size());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            new InputMemoryStore(getTargetContext()).remove(testId);
            result.putString("featureResult", "ERROR");
            result.putString("featureError", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static RootProcess find(List<RootProcess> values, int pid) {
        for (RootProcess value : values) if (value.pid == pid) return value;
        return null;
    }

    private static void require(boolean value, String label) {
        if (!value) throw new IllegalStateException("Failed: " + label);
    }
}
