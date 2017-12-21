package org.lbd.ifc2bot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.SimpleSelector;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDF;
import org.lbd.ifc2bot.rdfpath.InvRDFStep;
import org.lbd.ifc2bot.rdfpath.RDFStep;
import org.lbd.ns.BOT;
import org.lbd.ns.IfcOwl;
import org.lbd.ns.RDFS;

import be.ugent.IfcSpfReader;
import guidcompressor.GuidCompressor;

/*
* The GNU Affero General Public License
* 
* Copyright (c) 2017 Jyrki Oraskari (Jyrki.Oraskari@gmail.f)
* 
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
* 
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU Affero General Public License for more details.
* 
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

public class IfcOWL2BOT {

	private Model ifcowl_model;
	private Model ontology_model = null;
	private Map<String, List<Resource>> ifcowl_product_map = new HashMap<>();
	private final String uriBase;

	public IfcOWL2BOT(String ifc_filename, String uriBase) {
		this.uriBase = uriBase;
		ontology_model = ModelFactory.createDefaultModel();
		readInOntologies();
		createIfcBOTMapping();

		Model output_model = ModelFactory.createDefaultModel();
		RDFS.addNameSpace(output_model);
		BOT.addNameSpaces(output_model);
		IfcOwl.addNameSpace(output_model);
		ifcowl_model = readAndConvertIFC(ifc_filename, uriBase);

		
		listPropertysets().stream().map(rn -> rn.asResource()).forEach(propertyset -> {
			Resource pset = formatURI(propertyset, output_model, "PropertySet");
			addLabel(propertyset, pset);

			RDFStep[] path = { new RDFStep(IfcOwl.hasProperties_IfcPropertySet) };
			pathQuery(propertyset, path).forEach(propertySingleValue -> {
				Resource p = formatURI(propertySingleValue.asResource(), output_model, "Property");
				pset.addProperty(BOT.PropertySet.hasProperty, p);

				RDFStep[] name_path = { new RDFStep(IfcOwl.name_IfcProperty), new RDFStep(IfcOwl.hasString) };
				pathQuery(propertySingleValue.asResource(), name_path)
						.forEach(name -> p.addProperty(BOT.PropertySet.hasName, name));

				RDFStep[] value_path = { new RDFStep(IfcOwl.nominalValue_IfcPropertySingleValue),
						new RDFStep(IfcOwl.hasString) };
				pathQuery(propertySingleValue.asResource(), value_path)
						.forEach(name -> p.addProperty(BOT.PropertySet.hasValue, name));

			});
		});

		

		
		listSites().stream().map(rn -> rn.asResource()).forEach(site -> {
			Resource sio = formatURI(site, output_model, "Site");
			addLabel(site, sio);
			addDescription(site.asResource(), sio);
			sio.addProperty(RDF.type, BOT.site);

			listBuildings(site).stream().map(rn -> rn.asResource()).forEach(building -> {
				Resource bo = formatURI(building, output_model, "Building");
				addLabel(building, bo);
				addDescription(building, bo);
				bo.addProperty(RDF.type, BOT.building);
				sio.addProperty(BOT.hasBuilding, bo);

				listStoreys(building).stream().map(rn -> rn.asResource()).forEach(storey -> {
					Resource so = formatURI(storey, output_model, "Storey");
					addLabel(storey, so);
					addDescription(storey, so);

					bo.addProperty(BOT.hasStorey, so);
					so.addProperty(RDF.type, BOT.storey);

					listElements(storey).stream().map(rn -> rn.asResource()).forEach(element -> {
						Optional<String> predefined_type = getPredefinedData(element);
						Optional<Resource> ifcowl_type = getType(element);
						Optional<Resource> bot_type = Optional.empty();
						if (ifcowl_type.isPresent()) {
							bot_type = getBOTProductType(ifcowl_type.get().getLocalName());
						}

						if (bot_type.isPresent()) {
							Resource eo = formatURI(element, output_model, bot_type.get().getLocalName());
							addLabel(element, eo);
							addDescription(element, eo);
							if (predefined_type.isPresent()) {
								Resource product = output_model
										.createResource(bot_type.get().getURI() + "-" + predefined_type.get());
								eo.addProperty(RDF.type, product);
							} else
								eo.addProperty(RDF.type, bot_type.get());
							eo.addProperty(RDF.type, BOT.element);
							so.addProperty(BOT.containsElement, eo);

							listPropertysets(element).stream().map(rn -> rn.asResource()).forEach(propertyset -> {
								Resource pset = formatURI(propertyset, output_model, "PropertySet");
								eo.addProperty(BOT.PropertySet.hasPropertySet, pset);
							});
						}
					});

					listSpaces(storey.asResource()).stream().forEach(space -> {
						Resource spo = formatURI(space.asResource(), output_model, "Space");
						addLabel(space.asResource(), spo);
						addDescription(space.asResource(), spo);

						so.addProperty(BOT.hasSpace, spo);
						spo.addProperty(RDF.type, BOT.space);
					});
				});
			});
		});
        
		String out_filename=ifc_filename.split("\\.")[0]+"_BOT.ttl";
		
		try {
			FileOutputStream fo = new FileOutputStream(new File(out_filename));
			output_model.write(fo, "TTL");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		output_model.write(System.out, "TTL");

		System.out.println("Conversion done. File is: "+out_filename);
	}

	private Optional<Resource> getType(Resource r) {
		RDFStep[] path = { new RDFStep(RDFS.type) };
		return pathQuery(r, path).stream().map(rn -> rn.asResource()).findAny();
	}

	private Optional<String> getPredefinedData(RDFNode rn) {
		if (!rn.isResource())
			return Optional.empty();
		;
		final StringBuilder sb = new StringBuilder();
		rn.asResource().listProperties().toList().stream()
				.filter(t -> t.getPredicate().getLocalName().startsWith("predefinedType_"))
				.map(t -> t.getObject().asResource().getLocalName()).forEach(o -> sb.append(o));
		if (sb.length() == 0)
			return Optional.empty();
		return Optional.of(sb.toString());
	}

	private void addDescription(Resource ifc_r, final Resource bot_r) {
		ifc_r.listProperties(IfcOwl.description).toList()
				.forEach(x -> x.getObject().asResource().listProperties(IfcOwl.hasString)
						.forEachRemaining(y -> bot_r.addProperty(RDFS.comment, y.getObject())));
	}

	private void addLabel(Resource ifc_r, final Resource bot_r) {
		ifc_r.listProperties(IfcOwl.name).toList().forEach(x -> x.getObject().asResource()
				.listProperties(IfcOwl.hasString).forEachRemaining(y -> bot_r.addProperty(RDFS.label, y.getObject())));
	}

	private Resource formatURI(Resource r, Model m, String product_type) {
		String guid = getGUID(r);
		if (guid == null) {
			Resource uri = m.createResource(this.uriBase + product_type + "/" + r.getLocalName());
			return uri;
		} else {
			Resource guid_uri = m
					.createResource(this.uriBase + product_type + "/" + GuidCompressor.uncompressGuidString(guid));
			Literal l = m.createLiteral(guid);
			guid_uri.addLiteral(IfcOwl.guid_simple, l);
			return guid_uri;
		}
	}

	private String getGUID(Resource r) {
		StmtIterator i = r.listProperties(IfcOwl.guid);
		if (i.hasNext()) {
			Statement s = i.next();
			String guid = s.getObject().asResource().getProperty(IfcOwl.hasString).getObject().asLiteral()
					.getLexicalForm();
			return guid;
		}
		return null;
	}

	public Optional<Resource> getBOTProductType(String ifcType) {
		List<Resource> ret = ifcowl_product_map.get(ifcType);
		if (ret == null) {
			return Optional.empty();
		} else if (ret.size() > 1) {
			System.out.println("many " + ifcType);
			return Optional.empty();
		} else if (ret.size() > 0)
			return Optional.of(ret.get(0));
		else
			return Optional.empty();
	}

	private List<RDFNode> listSites() {
		RDFStep[] path = { new InvRDFStep(RDFS.type) };
		return pathQuery(ifcowl_model.getResource(IfcOwl.IfcSite), path);
	}

	private List<RDFNode> listBuildings(Resource site) {
		RDFStep[] path = { new InvRDFStep(IfcOwl.relatingObject_IfcRelDecomposes),
				new RDFStep(IfcOwl.relatedObjects_IfcRelDecomposes) };
		return pathQuery(site, path);
	}

	private List<RDFNode> listStoreys(Resource building) {
		RDFStep[] path = { new InvRDFStep(IfcOwl.relatingObject_IfcRelDecomposes),
				new RDFStep(IfcOwl.relatedObjects_IfcRelDecomposes) };
		return pathQuery(building, path);
	}

	private List<RDFNode> listSpaces(Resource storey) {
		RDFStep[] path = { new InvRDFStep(IfcOwl.relatingObject_IfcRelDecomposes),
				new RDFStep(IfcOwl.relatedObjects_IfcRelDecomposes) };
		return pathQuery(storey, path);
	}

	private List<RDFNode> listElements(Resource storey) {
		RDFStep[] path = { new InvRDFStep(IfcOwl.relatingStructure_IfcRelContainedInSpatialStructure),
				new RDFStep(IfcOwl.relatedElements_IfcRelContainedInSpatialStructure) };
		return pathQuery(storey, path);
	}

	private List<Resource> listElements() {
		final List<Resource> ret = new ArrayList<>();
		ifcowl_model.listStatements().filterKeep(t1 -> t1.getPredicate().equals(RDFS.type)).filterKeep(t2 -> {
			Optional<Resource> product_type = getBOTProductType(t2.getObject().asResource().getLocalName());
			return product_type.isPresent();
		}).mapWith(t1 -> t1.getSubject()).forEachRemaining(s -> ret.add(s));
		;
		return ret;
	}

	private List<RDFNode> listPropertysets(Resource resource) {
		RDFStep[] path = { new InvRDFStep(IfcOwl.relatedObjects_IfcRelDefines),
				new RDFStep(IfcOwl.relatingPropertyDefinition_IfcRelDefinesByProperties) };
		return pathQuery(resource, path);
	}

	private List<RDFNode> listPropertysets() {
		RDFStep[] path = { new InvRDFStep(RDFS.type) };
		return pathQuery(ifcowl_model.getResource(IfcOwl.IfcPropertySet), path);
	}

	private List<RDFNode> pathQuery(Resource r, RDFStep[] path) {
		List<RDFStep> path_list = Arrays.asList(path);
		if (r.getModel() == null)
			return new ArrayList<RDFNode>();
		Optional<RDFStep> step = path_list.stream().findFirst();
		if (step.isPresent()) {
			List<RDFNode> step_result = step.get().next(r);
			if (path.length > 1) {
				final List<RDFNode> result = new ArrayList<RDFNode>();
				step_result.stream().filter(rn1 -> rn1.isResource()).map(rn2 -> rn2.asResource()).forEach(r1 -> {
					List<RDFStep> tail = path_list.stream().skip(1).collect(Collectors.toList());
					result.addAll(pathQuery(r1, tail.toArray(new RDFStep[tail.size()])));
				});
				return result;
			} else
				return step_result;
		}
		return new ArrayList<RDFNode>();
	}

	public void createIfcBOTMapping() {
		StmtIterator si = ontology_model.listStatements();
		while (si.hasNext()) {
			Statement s = si.next();
			if (s.getPredicate().toString().toLowerCase().contains("seealso")) {
				if (s.getObject().isLiteral())
					continue;
				List<Resource> resource_list = ifcowl_product_map
						.getOrDefault(s.getObject().asResource().getLocalName(), new ArrayList<Resource>());
				ifcowl_product_map.put(s.getObject().asResource().getLocalName(), resource_list);
				resource_list.add(s.getSubject());

				StmtIterator superi = ontology_model
						.listStatements(new SimpleSelector(null, RDFS.subClassOf, s.getObject()));
				while (superi.hasNext()) {
					Statement su = superi.next();

					List<Resource> r_list = ifcowl_product_map.getOrDefault(su.getSubject().getLocalName(),
							new ArrayList<Resource>());
					ifcowl_product_map.put(su.getSubject().getLocalName(), r_list);
					r_list.add(s.getSubject());
				}

			}
		}
	}

	public Model readAndConvertIFC(String ifc_file, String uriBase) {
		try {
			IfcSpfReader rj = new IfcSpfReader();
			try {
				Model m = ModelFactory.createDefaultModel();
				ByteArrayOutputStream stringStream = new ByteArrayOutputStream();
				rj.convert(ifc_file, stringStream, uriBase);
				InputStream stream = new ByteArrayInputStream(
						stringStream.toString().getBytes(StandardCharsets.UTF_8.name()));
				m.read(stream, null, "TTL");
				return m;
			} catch (IOException e) {
				e.printStackTrace();
			}

		} catch (Exception e) {
			e.printStackTrace();

		}
		System.out.println("IFC-RDF conversion not done");
		return ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);
	}

	private void readInOntologies() {
		readInOntologyTTL("prod.ttl");
		readInOntologyTTL("prod_building_elements.ttl");
		readInOntologyTTL("prod_mep.ttl");
		readInOntologyTTL("prod_mep.ttl");
		readInOntologyTTL("IFC2X3_Final.ttl");
	}

	private void readInOntologyTTL(String ontology_file) {

		InputStream in = null;
		try {
			in = IfcOWL2BOT.class.getResourceAsStream(ontology_file);
			if (in == null) {
				try {
					in = new FileInputStream(new File("c:/jo/products/" + ontology_file));
				} catch (Exception e) {
					e.printStackTrace();
					return;
				}
			}
			ontology_model.read(in, null, "TTL");
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public static void main(String[] args) {
		if (args.length > 0)
			new IfcOWL2BOT(args[0], args[1]);
		else
			System.out.println("Usage: IfcOWL2BOT1 ifc_filename base_uri");
	}

}
