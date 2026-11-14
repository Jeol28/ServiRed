package co.edu.javeriana.servired.gateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** El dominio no depende del framework ni de la infraestructura (arquitectura hexagonal). */
@AnalyzeClasses(packages = "co.edu.javeriana.servired.gateway",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaTest {

    @ArchTest
    static final ArchRule dominioIndependiente = noClasses()
            .that().resideInAPackage("..dominio..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "reactor..", "jakarta..", "..infraestructura..");
}
