package kireiko.dev.anticheat.checks.aim.ml.modules.v5;

import kireiko.dev.millennium.ml.data.ResultML;
import kireiko.dev.millennium.ml.data.module.FlagType;
import kireiko.dev.millennium.ml.data.module.ModuleML;
import kireiko.dev.millennium.ml.data.module.ModuleResultML;
import kireiko.dev.millennium.ml.logic.ModelVer;

public class RNN2Module implements ModuleML {
    public static final String co = "m2-rnn";
    public static final double cp = 3.0;

    @Override
    public String getName() {
        return "m2-rnn";
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

    public static float a(FlagType type) {
        switch (type) {
            case SUSPECTED:
                return 1.4F;
            case STRANGE:
                return 1.0F;
            case UNUSUAL:
                return 0.8F;
            default:
                return -0.5F;
        }
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
