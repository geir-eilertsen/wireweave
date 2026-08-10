package net.fjordomatic.rest;

import net.fjordomatic.application.GetConceptsUseCase;
import net.fjordomatic.domain.Concept;
import net.fjordomatic.domain.ConceptGroup;
import net.fjordomatic.rest.ConceptsController.ConceptGroupDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConceptsControllerTest {

    @Mock GetConceptsUseCase getConceptsUseCase;

    @InjectMocks ConceptsController controller;

    @Test
    void mapsDomainGroupsAndConceptsToResponseDtos() {
        when(getConceptsUseCase.getConcepts()).thenReturn(List.of(
            new ConceptGroup("Machines", List.of(
                Concept.of("Machine", "Any host Fjord knows about.", "It's the unit you act on.")))));

        List<ConceptGroupDto> groups = controller.concepts();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).title()).isEqualTo("Machines");
        assertThat(groups.get(0).concepts()).hasSize(1);
        assertThat(groups.get(0).concepts().get(0).slug()).isEqualTo("machine");
        assertThat(groups.get(0).concepts().get(0).term()).isEqualTo("Machine");
        assertThat(groups.get(0).concepts().get(0).definition()).isEqualTo("Any host Fjord knows about.");
        assertThat(groups.get(0).concepts().get(0).whyYouCare()).isEqualTo("It's the unit you act on.");
    }
}
