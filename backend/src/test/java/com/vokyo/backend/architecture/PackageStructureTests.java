package com.vokyo.backend.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.context.annotation.Configuration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Guards the package layout the codebase already follows, so that drift fails the
 * build instead of accumulating. Maven's standard directory layout expects test
 * packages to mirror main packages; these rules encode the parts of that which are
 * easy to break by accident.
 */
@AnalyzeClasses(packages = "com.vokyo.backend")
class PackageStructureTests {

    @ArchTest
    static final ArchRule integration_tests_live_in_the_integration_package =
            classes()
                    .that().haveSimpleNameEndingWith("IntegrationTests")
                    .should().resideInAPackage("..integration..")
                    .because("Failsafe selects integration tests by the **/integration/*Tests.java glob");

    @ArchTest
    static final ArchRule unit_tests_stay_out_of_the_integration_package =
            noClasses()
                    .that().haveSimpleNameEndingWith("Tests")
                    .and().haveSimpleNameNotEndingWith("IntegrationTests")
                    .and().haveSimpleNameNotEndingWith("ApplicationTests")
                    .should().resideInAPackage("..integration..")
                    .because("Surefire excludes **/integration/**, so a unit test parked there never runs");

    @ArchTest
    static final ArchRule tests_do_not_sit_in_the_root_package =
            noClasses()
                    .that().haveSimpleNameEndingWith("Tests")
                    .should().resideInAPackage("com.vokyo.backend")
                    .because("the root package is the entry point namespace, not a bucket for tests");

    @ArchTest
    static final ArchRule there_is_no_catch_all_config_package =
            noClasses()
                    .should().resideInAPackage("..config..")
                    .because("configuration belongs in the domain package it configures");

    @ArchTest
    static final ArchRule spring_configuration_classes_use_the_Configuration_suffix =
            classes()
                    .that().areAnnotatedWith(Configuration.class)
                    .should().haveSimpleNameEndingWith("Configuration")
                    .because("the codebase standardised on Configuration over the shorter Config");
}
