package org.mdmi.rt.service.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.mdmi.core.Mdmi;
import org.mdmi.core.engine.MdmiUow;
import org.mdmi.core.engine.javascript.Utils;
import org.mdmi.core.engine.postprocessors.ConfigurablePostProcessor;
import org.mdmi.core.engine.preprocessors.ConfigurablePreProcessor;
import org.mdmi.core.engine.semanticprocessors.ConfigurableSemanticProcessor;
import org.mdmi.core.engine.terminology.FHIRTerminologyTransform;
import org.mdmi.core.runtime.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.Yaml;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Context;

@RestController
@RequestMapping("/mdmi/transformation")
public class MdmiEngine {

	@Autowired
	private ConfigurableApplicationContext applicationContext;

	@Autowired
	FHIRTerminologySettings terminologySettings;

	@Autowired
	MDMISettings mdmiSettings;

	@Autowired
	ServletContext context;

	static Boolean loaded = Boolean.FALSE;

	@Value("#{systemProperties['mdmi.maps'] ?: '/maps'}")
	private String mapsFolders;

	private HashMap<String, Properties> mapProperties = new HashMap<>();

	private HashMap<String, JSONObject> mapValues = new HashMap<>();

	private static Logger logger = LoggerFactory.getLogger(MdmiEngine.class);

	static List<Map<String, Object>> preprocessors = new ArrayList<Map<String, Object>>();

	static List<Map<String, Object>> postprocessors = new ArrayList<Map<String, Object>>();

	static List<Map<String, Object>> sourcesemanticprocessors = new ArrayList<Map<String, Object>>();

	static List<Map<String, Object>> targetsemanticprocessors = new ArrayList<Map<String, Object>>();

	static long lastModified;

	@SuppressWarnings("unchecked")
	private void loadMaps() throws IOException {

		synchronized (this) {

			MdmiUow.sourceFilter = mdmiSettings.getSourceFilterFlag();

			logger.info("sourceFilter status" + MdmiUow.sourceFilter);

			if (loaded || lastModified == 0) {
				long currentModified = 0;
				for (String mapsFolder : Stream.of(mapsFolders.split(",", -1)).collect(Collectors.toList())) {

					Set<Path> folders = Files.find(
						Paths.get(mapsFolder), Integer.MAX_VALUE,
						(filePath, fileAttr) -> fileAttr.isDirectory()).collect(Collectors.toSet());

					for (Path folder : folders) {

						Set<File> maps2 = Stream.of(new File(folder.toString()).listFiles()).filter(
							file -> (!file.isDirectory() && file.toString().endsWith("mdmi"))).collect(
								Collectors.toSet());

						for (File map : maps2) {
							if (map.lastModified() > currentModified) {
								currentModified = map.lastModified();
							}

						}

					}
				}

				if (currentModified > lastModified) {
					loaded = false;
					mapProperties.clear();
					mapValues.clear();
					preprocessors.clear();
					postprocessors.clear();
					targetsemanticprocessors.clear();
					sourcesemanticprocessors.clear();
					lastModified = currentModified;

				}

			}

			if (loaded) {
				return;
			}

			FHIRTerminologyTransform.codeValues.clear();

			FHIRTerminologyTransform.processTerminology = true;

			FHIRTerminologyTransform.setFHIRTerminologyURL(terminologySettings.getUrl());

			FHIRTerminologyTransform.setUserName(terminologySettings.getUserName());

			FHIRTerminologyTransform.setPassword(terminologySettings.getPassword());

			for (String mapsFolder : Stream.of(mapsFolders.split(",", -1)).collect(Collectors.toList())) {

				Set<Path> folders = Files.find(
					Paths.get(mapsFolder), Integer.MAX_VALUE, (filePath, fileAttr) -> fileAttr.isDirectory()).collect(
						Collectors.toSet());

				for (Path folder : folders) {

					Set<String> maps = Stream.of(new File(folder.toString()).listFiles()).filter(
						file -> (!file.isDirectory() && file.toString().endsWith("mdmi"))).map(File::getName).collect(
							Collectors.toSet());
					for (String map : maps) {
						logger.trace("Loading map  " + map);
						InputStream targetStream = new FileInputStream(folder.toString() + "/" + map);
						Mdmi.INSTANCE().getResolver().resolve(targetStream);
						logger.trace("Loaded map  " + map);
					}

					Path datatypemapsPath = Paths.get(folder.toString() + "/datatypemaps");
					if (Files.isDirectory(datatypemapsPath)) {
						Set<String> datatypemaps = Stream.of(
							new File(folder.toString() + "/datatypemaps").listFiles()).filter(
								file -> (!file.isDirectory() && file.toString().endsWith("js"))).map(
									File::getName).collect(Collectors.toSet());
						for (String datatypemap : datatypemaps) {
							logger.trace("Loading datatypemap  " + datatypemap);
							InputStream datatypetermStream = new FileInputStream(
								folder.toString() + "/datatypemaps/" + datatypemap);
							String mapsource = IOUtils.toString(datatypetermStream, StandardCharsets.UTF_8.name());

							for (String key : Mdmi.INSTANCE().getResolver().getMaps().keySet()) {
								if (key.startsWith(FilenameUtils.removeExtension(datatypemap))) {
									Mdmi.INSTANCE().getResolver().getMaps().get(key).datatypemappings = mapsource;
								}
							}
							logger.trace("Loaded datatypemap  " + datatypemap);
						}
					}

					Path termsPath = Paths.get(folder.toString() + "/terms");
					if (Files.isDirectory(termsPath)) {

						Set<String> valusets = Stream.of(new File(folder.toString() + "/terms").listFiles()).filter(
							file -> (!file.isDirectory() && file.toString().endsWith("json"))).map(
								File::getName).collect(Collectors.toSet());
						for (String valuset : valusets) {

							try {

								JSONParser parser = new JSONParser();

								Path file = Path.of(folder.toString() + "/terms/" + valuset);

								logger.trace("Loading valuset  " + valuset);

								String json = Files.readString(file, StandardCharsets.UTF_8);
								JSONObject jsonObject;

								jsonObject = (JSONObject) parser.parse(json);

								JSONObject expansion = (JSONObject) jsonObject.get("expansion");

								JSONArray contains = (JSONArray) expansion.get("contains");

								Utils.mapOfTransforms.put(FilenameUtils.removeExtension(valuset), new Properties());

								Consumer<JSONObject> getcode = new Consumer<JSONObject>() {

									@Override
									public void accept(JSONObject element) {
										Utils.mapOfTransforms.get(FilenameUtils.removeExtension(valuset)).put(
											element.get("code"), element.get("code"));

									}

								};
								contains.forEach(getcode);
							} catch (ParseException e) {
								logger.error(e.getMessage());
							}
						}

						Set<String> datatypeterms = Stream.of(
							new File(folder.toString() + "/terms").listFiles()).filter(
								file -> (!file.isDirectory() && file.toString().endsWith("properties"))).map(
									File::getName).collect(Collectors.toSet());
						for (String datatypeterm : datatypeterms) {
							logger.trace("Loading datatypeterm  " + datatypeterm);
							InputStream datatypetermStream = new FileInputStream(
								folder.toString() + "/terms/" + datatypeterm);
							Utils.mapOfTransforms.put(FilenameUtils.removeExtension(datatypeterm), new Properties());
							Utils.mapOfTransforms.get(FilenameUtils.removeExtension(datatypeterm)).load(
								datatypetermStream);

							logger.trace("Loaded map  " + datatypeterm);
						}

					}

					logger.trace("Check for processors.yml ");
					logger.trace("Looking for " + folder.toString() + "/" + "processors.yml");
					logger.trace("EXISTS " + Files.exists(Paths.get(folder.toString() + "/" + "processors.yml")));
					if (Files.exists(Paths.get(folder.toString() + "/" + "processors.yml"))) {
						logger.trace("Found processors.yml ");
						Yaml processorYaml = new Yaml();
						InputStream inputStream = new FileInputStream(folder.toString() + "/" + "processors.yml");
						Map<String, Object> obj = processorYaml.load(inputStream);

						if (obj != null) {
							if (obj.containsKey("preprocessors")) {
								preprocessors.add((Map<String, Object>) obj.get("preprocessors"));
							}
							if (obj.containsKey("postprocessors")) {
								postprocessors.add((Map<String, Object>) obj.get("postprocessors"));
							}

							if (obj.containsKey("sourcesemanticprocessors")) {
								sourcesemanticprocessors.add((Map<String, Object>) obj.get("sourcesemanticprocessors"));
							}

							if (obj.containsKey("targetsemanticprocessors")) {
								targetsemanticprocessors.add((Map<String, Object>) obj.get("targetsemanticprocessors"));
							}
						}

					}

					Set<String> propertyFiles = Stream.of(new File(folder.toString()).listFiles()).filter(
						file -> (!file.isDirectory() && file.toString().endsWith("properties"))).map(
							File::getName).collect(Collectors.toSet());

					for (String propertyFile : propertyFiles) {
						logger.trace("Loading property  " + propertyFile);
						InputStream targetStream = new FileInputStream(folder.toString() + "/" + propertyFile);
						Properties properties = new Properties();
						properties.load(targetStream);
						mapProperties.put(FilenameUtils.removeExtension(propertyFile), properties);
						logger.trace("Loaded property  " + propertyFile);
					}

					Set<String> valuesFiles = Stream.of(new File(folder.toString()).listFiles()).filter(
						file -> (!file.isDirectory() && file.toString().endsWith("json"))).map(File::getName).collect(
							Collectors.toSet());

					for (String valuesFile : valuesFiles) {
						logger.trace("Loading property  " + valuesFile);

						// Files.readString()

						InputStream targetStream = new FileInputStream(folder.toString() + "/" + valuesFile);

						String body = IOUtils.toString(targetStream, StandardCharsets.UTF_8.name());

						JSONParser parser = new JSONParser();
						JSONObject jsonObject;
						try {
							jsonObject = (JSONObject) parser.parse(body);
							mapValues.put(FilenameUtils.removeExtension(valuesFile), jsonObject);
							logger.trace("Loaded property  " + valuesFile);
						} catch (ParseException e) {
							logger.error(e.getLocalizedMessage());
						}
					}

				}

			}

			loaded = Boolean.TRUE;

		}

	}

	private void reloadMaps() throws IOException {
		synchronized (this) {
			loaded = false;
			mapProperties.clear();
			preprocessors.clear();
			postprocessors.clear();
			targetsemanticprocessors.clear();
			sourcesemanticprocessors.clear();
			loadMaps();
		}
	}

	private Properties getMapProperties(String target) {
		for (String mapsFolder : Stream.of(mapsFolders.split(",", -1)).collect(Collectors.toList())) {
			if (!mapProperties.containsKey(target)) {
				Properties properties = new Properties();
				Path propertyFile = Paths.get(context.getRealPath(mapsFolder + "/" + target + ".properties"));

				if (Files.exists(propertyFile)) {
					try {
						properties.load(Files.newInputStream(propertyFile));
					} catch (IOException e) {
					}
				}
				Path valuesFile = Paths.get(context.getRealPath(mapsFolder + "/" + target + ".json"));
				if (Files.exists(valuesFile)) {
					try {
						properties.put("InitialValues", new String(Files.readAllBytes(valuesFile)));
					} catch (IOException e) {
					}
				}
				mapProperties.put(target, properties);
			}
		}
		return mapProperties.get(target);
	}

	@GetMapping
	public String get(HttpServletRequest req) throws Exception {
		loadMaps();
		loadPreProcessors(Mdmi.INSTANCE());
		loadPostProcessors(Mdmi.INSTANCE());
		loadsourcesemanticprocessors(Mdmi.INSTANCE());
		loadTargetSemanticProcessors(Mdmi.INSTANCE());

		FHIRTerminologyTransform terminologyService = new FHIRTerminologyTransform();

		terminologyService.healthCheck();

		return Mdmi.INSTANCE().getResolver().getEngineConfigurations();
	}

	@GetMapping(path = "reset")
	public String reset(HttpServletRequest req) throws Exception {
		reloadMaps();
		return Mdmi.INSTANCE().getResolver().getEngineConfigurations();
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = {
			MediaType.APPLICATION_XML_VALUE, MediaType.APPLICATION_JSON_VALUE })
	public String transformation(@Context HttpServletRequest req, @RequestParam("source") String source,
			@RequestParam("target") String target, @RequestPart("message") MultipartFile uploadedInputStream)
			throws Exception {
		logger.debug("DEBUG Start transformation ");
		loadMaps();
		loadPreProcessors(Mdmi.INSTANCE());
		loadPostProcessors(Mdmi.INSTANCE());
		loadsourcesemanticprocessors(Mdmi.INSTANCE());
		loadTargetSemanticProcessors(Mdmi.INSTANCE());
		// Mdmi.INSTANCE().getSourceSemanticModelProcessors().addSourceSemanticProcessor(new ProcessRelationships());
		getMapProperties(source);
		getMapProperties(target);

		String result = RuntimeService.runTransformation(
			source, uploadedInputStream.getBytes(), target, null, mapProperties.get(source), mapProperties.get(target),
			mapValues.get(source), mapValues.get(target));
		return result;
	}

	@PostMapping(path = "byvalue", consumes = {
			MediaType.APPLICATION_XML_VALUE, MediaType.APPLICATION_JSON_VALUE }, produces = {
					MediaType.APPLICATION_XML_VALUE, MediaType.APPLICATION_JSON_VALUE })
	public String transformation2(@Context HttpServletRequest req, @RequestParam("source") String source,
			@RequestParam("target") String target, @RequestBody String message) throws Exception {
		logger.debug("DEBUG Start transformation ");
		loadMaps();
		loadPreProcessors(Mdmi.INSTANCE());
		loadPostProcessors(Mdmi.INSTANCE());
		loadsourcesemanticprocessors(Mdmi.INSTANCE());
		loadTargetSemanticProcessors(Mdmi.INSTANCE());
		// MdmiUow.setSerializeSemanticModel(false);
		// Mdmi.INSTANCE().getSourceSemanticModelProcessors().addSourceSemanticProcessor(new ProcessRelationships());
		getMapProperties(source);
		getMapProperties(target);

		String result = RuntimeService.runTransformation(
			source, message.getBytes(), target, null, mapProperties.get(source), mapProperties.get(target),
			mapValues.get(source), mapValues.get(target));
		return result;
	}

	private void loadPostProcessors(Mdmi instance) {

		instance.getPostProcessors().getPostProcessors().clear();
		if (postprocessors != null && (!postprocessors.isEmpty())) {

			for (Map<String, Object> p : postprocessors) {

				for (Object key : p.keySet()) {

					try {
						logger.trace("Adding postprocessors " + key);
						Class<?> clazz;
						clazz = Class.forName((String) ((Map) p.get(key)).get("class"));
						logger.trace("Loaded java postprocessors " + clazz.getCanonicalName());
						Constructor<?> ctors = clazz.getConstructors()[0];
						ConfigurablePostProcessor postProcessor = (ConfigurablePostProcessor) ctors.newInstance();
						postProcessor.setName((String) ((Map) p.get(key)).get("name"));
						postProcessor.setGroups((ArrayList<String>) ((Map) p.get(key)).get("groups"));
						postProcessor.setArguments(((Map) p.get(key)).get("arguments"));
						instance.getPostProcessors().addPostProcessor(postProcessor);
					} catch (Exception e) {
						logger.error("Error loading  PostProcessor " + key, e.getMessage());
						e.printStackTrace();
					}

				}
			}

		} else {
			logger.trace("No postprocessors registered");
		}

	}

	private void loadPreProcessors(Mdmi instance) {

		instance.getPreProcessors().getPreProcessors().clear();
		if (preprocessors != null && (!preprocessors.isEmpty())) {
			for (Map<String, Object> p : preprocessors) {
				for (Object key : p.keySet()) {
					try {
						logger.trace("Adding preprocessors " + key);
						Class<?> clazz;
						clazz = Class.forName((String) ((Map) p.get(key)).get("class"));
						logger.trace("Loaded java preprocessors " + clazz.getCanonicalName());
						Constructor<?> ctors = clazz.getConstructors()[0];
						ConfigurablePreProcessor preProcessor = (ConfigurablePreProcessor) ctors.newInstance();
						preProcessor.setName((String) ((Map) p.get(key)).get("name"));
						logger.trace("Loaded java preprocessors groups " + ((Map) p.get(key)).get("groups").toString());
						preProcessor.setGroups((ArrayList<String>) ((Map) p.get(key)).get("groups"));
						preProcessor.setArguments(((Map) p.get(key)).get("arguments"));
						instance.getPreProcessors().addPreProcessor(preProcessor);
					} catch (Exception e) {
						logger.error("Error loading  PostProcessor " + key, e.getMessage());
						e.printStackTrace();
					}

				}
			}
		}

	}

	private void loadsourcesemanticprocessors(Mdmi instance) {
		instance.getSourceSemanticModelProcessors().getSourceSemanticProcessors().clear();
		if (sourcesemanticprocessors != null && !sourcesemanticprocessors.isEmpty()) {
			for (Map<String, Object> p : sourcesemanticprocessors) {
				for (Object key : p.keySet()) {
					try {
						Class<?> clazz;
						clazz = Class.forName((String) ((Map) p.get(key)).get("class"));
						Constructor<?> ctors = clazz.getConstructor(null);
						ConfigurableSemanticProcessor sourceSemanticProcessor = (ConfigurableSemanticProcessor) ctors.newInstance();
						sourceSemanticProcessor.setName((String) ((Map) p.get(key)).get("name"));
						sourceSemanticProcessor.setGroups((ArrayList<String>) ((Map) p.get(key)).get("groups"));
						sourceSemanticProcessor.setArguments(((Map) p.get(key)).get("arguments"));
						instance.getSourceSemanticModelProcessors().addSourceSemanticProcessor(sourceSemanticProcessor);
					} catch (Exception e) {
						logger.error("Error loading  sourceSemanticProcessor " + key, e.getMessage());
						e.printStackTrace();
					}

				}
			}
		}

	}

	private void loadTargetSemanticProcessors(Mdmi instance) {
		instance.getTargetSemanticModelProcessors().getTargetSemanticProcessors().clear();
		if (targetsemanticprocessors != null && !targetsemanticprocessors.isEmpty()) {
			for (Map<String, Object> p : targetsemanticprocessors) {
				for (Object key : p.keySet()) {
					try {
						Class<?> clazz;
						clazz = Class.forName((String) ((Map) p.get(key)).get("class"));
						Constructor<?> ctors = clazz.getConstructor(null);
						ConfigurableSemanticProcessor targetSemanticProcessor = (ConfigurableSemanticProcessor) ctors.newInstance();
						targetSemanticProcessor.setName((String) ((Map) p.get(key)).get("name"));
						targetSemanticProcessor.setGroups((ArrayList<String>) ((Map) p.get(key)).get("groups"));
						targetSemanticProcessor.setArguments(((Map) p.get(key)).get("arguments"));
						instance.getTargetSemanticModelProcessors().addTargetSemanticProcessor(targetSemanticProcessor);
					} catch (Exception e) {
						logger.error("Error loading  targetSemanticProcessor " + key, e.getMessage());
						e.printStackTrace();
					}

				}
			}
		}

	}

}
