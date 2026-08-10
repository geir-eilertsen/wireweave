package net.fjordomatic.application.service;

import net.fjordomatic.application.GetConceptsUseCase;
import net.fjordomatic.domain.ConceptGroup;
import net.fjordomatic.domain.OperatorGlossary;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConceptsService implements GetConceptsUseCase {

    @Override
    public List<ConceptGroup> getConcepts() {
        // The curated, grouped operator glossary is a domain decision — it lives in the domain.
        return OperatorGlossary.groups();
    }
}
