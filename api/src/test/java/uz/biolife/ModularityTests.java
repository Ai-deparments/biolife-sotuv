package uz.biolife;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Fails the build if a module reaches into another module's internals. Also writes docs/modules/*.puml. */
class ModularityTests {
    static final ApplicationModules modules = ApplicationModules.of(BiolifeApplication.class);

    @Test
    void modulesRespectBoundaries() {
        modules.verify();
    }

    @Test
    void writeDocumentation() {
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
