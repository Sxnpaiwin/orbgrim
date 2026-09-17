package kireiko.dev.anticheat.checks.aim.ml.modules.v5;

import kireiko.dev.millennium.ml.data.ResultML;
import kireiko.dev.millennium.ml.data.module.FlagType;
import kireiko.dev.millennium.ml.data.module.ModuleML;
import kireiko.dev.millennium.ml.data.module.ModuleResultML;
import kireiko.dev.millennium.ml.logic.ModelVer;

public class RNN3Module implements ModuleML {
    public static final String co = "m3-rnn";

    @Override
    public String getName() {
        return "m3-rnn";
    }

    @Override
    public ModuleResultML getResult(ResultML resultML) {
        double p = resultML.statisticsResult.UNUSUAL;
        if (p > 0.9) {
            return new ModuleResultML(20, FlagType.SUSPECTED, "Insane Probability " + this.j(p));
        } else if (p > 0.8) {
            return new ModuleResultML(12, FlagType.SUSPECTED, "Suspicious Probability " + this.j(p));
        } else if (p > 0.7) {
            return new ModuleResultML(8, FlagType.STRANGE, "Strange Behavior " + this.j(p));
        } else {
            return p > 0.6 ? new ModuleResultML(4, FlagType.UNUSUAL, "Unusual patterns " + this.j(p)) : new ModuleResultML(0, FlagType.NORMAL, this.j(p));
        }
    }

    private String j(double v) {
        return String.format("%.1f%%", v * 100.0);
    }

    @Override
    public int getParameterBuffer() {
        return 32;
    }

    @Override
    public ModelVer getVersion() {
        return ModelVer.VERSION_5;
    }
}
