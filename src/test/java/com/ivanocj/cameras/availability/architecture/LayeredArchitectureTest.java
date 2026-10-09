package com.ivanocj.cameras.availability.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LayeredArchitectureTest {
    private static final String BASE = "com.ivanocj.cameras.availability";

    @Test
    void productionClassesRespectTheNamespaceAndLayerDependencyContract() {
        var production = new ClassFileImporter().importPath(Path.of("target/classes"));
        CompositeArchRule.of(classes().should().resideInAnyPackage(
                        BASE + ".domain..", BASE + ".application..", BASE + ".infrastructure..",
                        BASE + ".presentation..", BASE + ".bootstrap.."))
                .and(layeredArchitecture().consideringOnlyDependenciesInLayers()
                        .layer("Domain").definedBy(BASE + ".domain..")
                        .layer("Application").definedBy(BASE + ".application..")
                        .layer("Infrastructure").definedBy(BASE + ".infrastructure..")
                        .layer("Presentation").definedBy(BASE + ".presentation..")
                        .layer("Bootstrap").definedBy(BASE + ".bootstrap..")
                        .whereLayer("Bootstrap").mayNotBeAccessedByAnyLayer()
                        .whereLayer("Presentation").mayOnlyBeAccessedByLayers("Bootstrap")
                        .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Bootstrap")
                        .whereLayer("Application").mayOnlyBeAccessedByLayers("Presentation", "Infrastructure", "Bootstrap")
                        .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Presentation", "Bootstrap"))
                .and(noClasses().that().resideInAnyPackage(BASE + ".domain..", BASE + ".application..")
                        .should().dependOnClassesThat().resideInAnyPackage("java.awt..", "javax.swing..", "com.formdev.flatlaf.."))
                .and(noClasses().that().resideInAnyPackage(BASE + ".domain..", BASE + ".application..", BASE + ".presentation..")
                        .should().dependOnClassesThat().resideInAnyPackage("ch.qos.logback.."))
                .and(classes().that().haveSimpleName("AvailabilityCheckService").should().resideInAPackage(BASE + ".application.service"))
                .and(classes().that().haveSimpleName("UdpSessionProbe").should().resideInAPackage(BASE + ".infrastructure.probe"))
                .and(classes().that().haveSimpleName("TransportProtocol").should().resideInAPackage(BASE + ".domain.model"))
                .and(classes().that().haveSimpleName("AvailabilityPanel").should().resideInAPackage(BASE + ".presentation.swing"))
                .and(noClasses().that().resideInAPackage(BASE + ".presentation")
                        .should().dependOnClassesThat().resideInAnyPackage("java.awt..", "javax.swing..", "com.formdev.flatlaf.."))
                .check(production);
    }
}
