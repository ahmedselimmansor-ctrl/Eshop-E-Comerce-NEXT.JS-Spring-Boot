package com.eshop.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.properties.HasReturnType;
import com.tngtech.archunit.lang.conditions.ArchConditions;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Structural rules, enforced rather than described.
 *
 * <p>Most of these exist because the alternative is a defect that compiles. A controller that
 * queries a repository directly and maps the result is the shape that produced every
 * {@code LazyInitializationException} in this codebase: with {@code open-in-view} disabled, the
 * entity is detached by the time the response mapper reads it, and nothing catches that until the
 * endpoint is actually called.
 */
class LayeringTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.eshop");
    }

    @Test
    @DisplayName("entities never depend on the web layer")
    void domainDoesNotDependOnWeb() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAPackage("..web..")
                .because("""
                        an entity that knows about a response DTO cannot be reused by anything else, \
                        and the dependency always ends up pointing back the other way too""");

        rule.check(classes);
    }

    @Test
    @DisplayName("repositories never depend on the web layer")
    void repositoriesDoNotDependOnWeb() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAPackage("..web..");

        rule.check(classes);
    }

    @Test
    @DisplayName("no controller injects a repository")
    void controllersDoNotInjectRepositories() {
        ArchRule rule = noClasses()
                .that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().areAssignableTo(Repository.class)
                .because("""
                        a controller holding a repository queries outside a transaction and maps the \
                        result after the session has closed, which is how every \
                        LazyInitializationException in this codebase happened - put the query and \
                        the mapping in a @Transactional service instead""");

        rule.check(classes);
    }

    @Test
    @DisplayName("no controller method returns or holds a JPA entity")
    void controllersDoNotHandleEntities() {
        // Five separate LazyInitializationException bugs in this codebase had the same shape: a
        // controller took an entity back from a service and read something off it after the
        // transaction had closed. Each compiled cleanly and failed at runtime, and the last one only
        // ever failed on a *retry*, so it survived until refunds were tested properly.
        //
        // A controller that never holds an entity cannot make the mistake.
        //
        // Scoped to the admin layer, and that is a deliberate admission rather than a design: the
        // storefront controllers violate this in 57 places. Most are safe today because their
        // queries declare an @EntityGraph, but "safe because of a fetch plan somewhere else" is
        // exactly the property that quietly stops holding. Widening this rule means moving those
        // mappings into their services, which is worth doing and is not a change to make blind.
        ArchRule rule = noClasses()
                .that().areAnnotatedWith(RestController.class)
                .and().resideInAPackage("com.eshop.admin.web..")
                .should(ArchConditions.callMethodWhere(
                        JavaCall.Predicates.target(
                                HasReturnType.Predicates.rawReturnType(
                                        DescribedPredicate.describe(
                                                "a JPA entity",
                                                (JavaClass target) ->
                                                        target.isAnnotatedWith(Entity.class))))))
                .because("""
                        the entity is detached by the time the controller sees it, so reading a lazy \
                        association off it throws at request time - return a finished DTO from a \
                        @Transactional service instead""");

        rule.check(classes);
    }

    @Test
    @DisplayName("controllers live in a web package")
    void controllersAreInWebPackages() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(RestController.class)
                .should().resideInAPackage("..web..");

        rule.check(classes);
    }

    @Test
    @DisplayName("business entities are only in domain packages")
    void entitiesAreInDomainPackages() {
        // com.eshop.common is excluded on purpose. The idempotency key and the outbox event are
        // persisted infrastructure, not part of any business module's model, and moving them under
        // a "domain" package would imply a domain they do not belong to.
        ArchRule rule = classes()
                .that().areAnnotatedWith(Entity.class)
                .and().resideOutsideOfPackage("com.eshop.common..")
                .should().resideInAPackage("..domain..");

        rule.check(classes);
    }

    @Test
    @DisplayName("the storefront never depends on the admin API")
    void storefrontDoesNotDependOnAdmin() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.eshop.catalog..")
                .or().resideInAPackage("com.eshop.cart..")
                .or().resideInAPackage("com.eshop.order..")
                .should().dependOnClassesThat().resideInAPackage("com.eshop.admin..")
                .because("""
                        the admin module is a consumer of these; a dependency back the other way \
                        would make the storefront un-deployable without it""");

        rule.check(classes);
    }
}
