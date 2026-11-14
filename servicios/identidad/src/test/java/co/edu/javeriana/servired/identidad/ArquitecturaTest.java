package co.edu.javeriana.servired.identidad;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Reglas de la arquitectura hexagonal: dominio y aplicación no conocen el framework. */
@AnalyzeClasses(packages = "co.edu.javeriana.servired.identidad",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaTest {

    @ArchTest
    static final ArchRule dominioIndependiente = noClasses()
            .that().resideInAPackage("..dominio..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta..",
                    "..aplicacion..", "..infraestructura..");

    @ArchTest
    static final ArchRule aplicacionSinInfraestructura = noClasses()
            .that().resideInAPackage("..aplicacion..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework.web..", "org.springframework.jdbc..",
                    "org.springframework.amqp..", "jakarta.servlet..", "..infraestructura..");
}
