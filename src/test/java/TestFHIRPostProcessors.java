
/*******************************************************************************
 * Copyright (c) 2025 seanmuir.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     seanmuir - initial API and implementation
 *
 *******************************************************************************/

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.Test;
import org.mdmi.processors.fhir.FHIRR4PostProcessorJson;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.util.FhirTerser;

/**
 * @author seanmuir
 *
 */
class TestFHIRPostProcessors {

	@Test
	void test() throws IOException {
		FHIRR4PostProcessorJson processor = new FHIRR4PostProcessorJson();

		FhirContext ctx = FhirContext.forR4Cached();
		String input = Files.readString(Path.of("src/test/IMATEncountersMin.json"));

		IParser jsonParser = ctx.newJsonParser().setPrettyPrint(true);
		// IParserErrorHandler mdmiParserErrorHandler = new MDMIParserErrorHandler();
		// parse.setParserErrorHandler(mdmiParserErrorHandler);

		// HashMap<String, String> referenceMappings = new HashMap<>();
		Bundle bundle = jsonParser.parseResource(Bundle.class, input);
		// processor.deduplicate(bundle);

		deduplicateDomainResources(bundle, ctx);

		String prettyJson = jsonParser.encodeResourceToString(bundle);

		System.out.println(prettyJson);

		// MessageModel model = null;
		// MdmiMessage message = new MdmiMessage();
		// message.setData(input);
		// processor.processMessage(model, message);
	}

	public static Bundle deduplicateOrganizations(Bundle bundle, FhirContext ctx) {

		Map<String, String> identifierToMasterRef = new HashMap<>();
		Map<String, String> duplicateRefMap = new HashMap<>();
		List<Bundle.BundleEntryComponent> entries = bundle.getEntry();

		// Step 1: Identify master orgs & duplicates
		for (Bundle.BundleEntryComponent entry : entries) {

			if (!(entry.getResource() instanceof Organization org)) {
				continue;
			}

			if (org.getIdentifier().isEmpty()) {
				continue; // skip orgs without identifier
			}

			Identifier id = org.getIdentifierFirstRep();
			String key = id.getSystem() + "|" + id.getValue();

			String entryRef = entry.getFullUrl();
			if (entryRef == null && org.getIdElement() != null) {
				entryRef = org.getIdElement().toUnqualifiedVersionless().getValue();
			}

			if (!identifierToMasterRef.containsKey(key)) {
				identifierToMasterRef.put(key, entryRef);
			} else {
				duplicateRefMap.put(entryRef, identifierToMasterRef.get(key));
			}
		}

		if (duplicateRefMap.isEmpty()) {
			return bundle; // nothing to dedupe
		}

		FhirTerser terser = ctx.newTerser();

		// Step 2: Update references across ALL resources
		for (Bundle.BundleEntryComponent entry : entries) {

			Resource resource = entry.getResource();
			if (resource == null)
				continue;

			List<Reference> refs = terser.getAllPopulatedChildElementsOfType(resource, Reference.class);

			for (Reference ref : refs) {
				String refValue = ref.getReference();

				if (duplicateRefMap.containsKey(refValue)) {
					ref.setReference(duplicateRefMap.get(refValue));
				}
			}
		}

		// Step 3: Remove duplicate Organization entries
		entries.removeIf(
			entry -> entry.getResource() instanceof Organization && duplicateRefMap.containsKey(entry.getFullUrl()));

		return bundle;
	}

	public static Bundle deduplicateResources(Bundle bundle, FhirContext ctx) {

		Map<String, String> identifierToMasterRef = new HashMap<>();
		Map<String, String> duplicateRefMap = new HashMap<>();
		List<Bundle.BundleEntryComponent> entries = bundle.getEntry();

		// Resource types to deduplicate
		Set<Class<? extends Resource>> supportedTypes = Set.of(Organization.class, Practitioner.class, Location.class);

		// Step 1: Identify master resources & duplicates
		for (Bundle.BundleEntryComponent entry : entries) {

			Resource resource = entry.getResource();
			if (resource == null || !supportedTypes.contains(resource.getClass())) {
				continue;
			}

			if (!(resource instanceof DomainResource domain)) {
				continue;
			}

			if (resource instanceof org.hl7.fhir.r4.model.Resource r) {

				if (!(r instanceof org.hl7.fhir.r4.model.Resource)) {
					continue;
				}
			}

			List<Identifier> identifiers = null;

			if (resource instanceof Organization org) {
				identifiers = org.getIdentifier();
			} else if (resource instanceof Practitioner practitioner) {
				identifiers = practitioner.getIdentifier();
			} else if (resource instanceof Location location) {
				identifiers = location.getIdentifier();
			}

			if (identifiers == null || identifiers.isEmpty()) {
				continue; // skip if no identifier
			}

			Identifier id = identifiers.get(0);
			String key = resource.getClass().getSimpleName() + "|" + id.getSystem() + "|" + id.getValue();

			String entryRef = entry.getFullUrl();
			if (entryRef == null && resource.getIdElement() != null) {
				entryRef = resource.getIdElement().toUnqualifiedVersionless().getValue();
			}

			if (!identifierToMasterRef.containsKey(key)) {
				identifierToMasterRef.put(key, entryRef);
			} else {
				duplicateRefMap.put(entryRef, identifierToMasterRef.get(key));
			}
		}

		if (duplicateRefMap.isEmpty()) {
			return bundle;
		}

		FhirTerser terser = ctx.newTerser();

		// Step 2: Update references across ALL resources
		for (Bundle.BundleEntryComponent entry : entries) {

			Resource resource = entry.getResource();
			if (resource == null)
				continue;

			List<Reference> refs = terser.getAllPopulatedChildElementsOfType(resource, Reference.class);

			for (Reference ref : refs) {
				String refValue = ref.getReference();

				if (refValue != null && duplicateRefMap.containsKey(refValue)) {
					ref.setReference(duplicateRefMap.get(refValue));
				}
			}
		}

		// Step 3: Remove duplicate entries
		entries.removeIf(entry -> {
			Resource resource = entry.getResource();
			if (resource == null)
				return false;

			boolean supported = supportedTypes.contains(resource.getClass());
			return supported && duplicateRefMap.containsKey(entry.getFullUrl());
		});

		return bundle;
	}

	// public static Bundle deduplicateDomainResources(Bundle bundle, FhirContext ctx) {
	//
	// Map<String, String> identifierToMasterRef = new HashMap<>();
	// Map<String, String> duplicateRefMap = new HashMap<>();
	// List<Bundle.BundleEntryComponent> entries = bundle.getEntry();
	//
	// // Step 1: Identify master resources & duplicates
	// for (Bundle.BundleEntryComponent entry : entries) {
	//
	// Resource resource = entry.getResource();
	// if (resource == null)
	// continue;
	//
	// // Only DomainResource types
	// if (!(resource instanceof DomainResource))
	// continue;
	//
	// // Must support identifiers
	// if (!(resource instanceof IBaseHasIdentifier hasIdentifier))
	// continue;
	//
	// List<Identifier> identifiers = hasIdentifier.getIdentifier();
	// if (identifiers == null || identifiers.isEmpty())
	// continue;
	//
	// Identifier id = identifiers.get(0);
	//
	// if (id.getSystem() == null || id.getValue() == null)
	// continue;
	//
	// String key = resource.fhirType() + "|" + id.getSystem() + "|" + id.getValue();
	//
	// String entryRef = entry.getFullUrl();
	// if (entryRef == null && resource.getIdElement() != null) {
	// entryRef = resource.getIdElement().toUnqualifiedVersionless().getValue();
	// }
	//
	// if (entryRef == null)
	// continue;
	//
	// if (!identifierToMasterRef.containsKey(key)) {
	// identifierToMasterRef.put(key, entryRef);
	// } else {
	// duplicateRefMap.put(entryRef, identifierToMasterRef.get(key));
	// }
	// }
	//
	// if (duplicateRefMap.isEmpty()) {
	// return bundle;
	// }
	//
	// FhirTerser terser = ctx.newTerser();
	//
	// // Step 2: Update ALL references in ALL resources
	// for (Bundle.BundleEntryComponent entry : entries) {
	//
	// Resource resource = entry.getResource();
	// if (resource == null)
	// continue;
	//
	// List<Reference> refs = terser.getAllPopulatedChildElementsOfType(resource, Reference.class);
	//
	// for (Reference ref : refs) {
	//
	// String refValue = ref.getReference();
	// if (refValue == null)
	// continue;
	//
	// if (duplicateRefMap.containsKey(refValue)) {
	// ref.setReference(duplicateRefMap.get(refValue));
	// }
	// }
	// }
	//
	// // Step 3: Remove duplicate entries
	// entries.removeIf(entry -> {
	// String fullUrl = entry.getFullUrl();
	// return fullUrl != null && duplicateRefMap.containsKey(fullUrl);
	// });
	//
	// return bundle;
	// }

	public static Bundle deduplicateDomainResources(Bundle bundle, FhirContext ctx) {

		Map<String, String> identifierToMasterRef = new HashMap<>();
		Map<String, String> duplicateRefMap = new HashMap<>();
		List<Bundle.BundleEntryComponent> entries = bundle.getEntry();

		FhirTerser terser = ctx.newTerser();

		// Step 1: Identify master resources & duplicates
		for (Bundle.BundleEntryComponent entry : entries) {

			Resource resource = entry.getResource();
			if (!(resource instanceof DomainResource))
				continue;

			// Dynamically fetch identifier elements
			List<IBase> identifiers = terser.getValues(resource, "identifier");
			if (identifiers == null || identifiers.isEmpty())
				continue;

			Identifier id = (Identifier) identifiers.get(0);

			if (id.getSystem() == null || id.getValue() == null)
				continue;

			String key = resource.fhirType() + "|" + id.getSystem() + "|" + id.getValue();

			String entryRef = entry.getFullUrl();
			if (entryRef == null && resource.getIdElement() != null) {
				entryRef = resource.getIdElement().toUnqualifiedVersionless().getValue();
			}

			if (entryRef == null)
				continue;

			if (!identifierToMasterRef.containsKey(key)) {
				identifierToMasterRef.put(key, entryRef);
			} else {
				duplicateRefMap.put(entryRef, identifierToMasterRef.get(key));
			}
		}

		if (duplicateRefMap.isEmpty()) {
			return bundle;
		}

		// Step 2: Update ALL references in ALL resources
		for (Bundle.BundleEntryComponent entry : entries) {

			Resource resource = entry.getResource();
			if (resource == null)
				continue;

			List<Reference> refs = terser.getAllPopulatedChildElementsOfType(resource, Reference.class);

			for (Reference ref : refs) {
				String refValue = ref.getReference();

				if (refValue != null && duplicateRefMap.containsKey(refValue)) {
					ref.setReference(duplicateRefMap.get(refValue));
				}
			}
		}

		// Step 3: Remove duplicate entries
		entries.removeIf(entry -> entry.getFullUrl() != null && duplicateRefMap.containsKey(entry.getFullUrl()));

		return bundle;
	}

	// Optional helper for pretty printing
	public static String toPrettyJson(Bundle bundle, FhirContext ctx) {
		IParser parser = ctx.newJsonParser().setPrettyPrint(true);
		return parser.encodeResourceToString(bundle);
	}

}
