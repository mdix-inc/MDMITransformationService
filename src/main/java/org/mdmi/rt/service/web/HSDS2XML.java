/*******************************************************************************
 * Copyright (c) 2018 seanmuir.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     seanmuir - initial API and implementation
 *
 *******************************************************************************/
package org.mdmi.rt.service.web;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.mdmi.Bag;
import org.mdmi.MessageModel;
import org.mdmi.core.MdmiMessage;
import org.mdmi.core.engine.preprocessors.IPreProcessor;

import com.helger.commons.csv.CSVReader;

/**
 * @author seanmuir
 *
 */
public class HSDS2XML implements IPreProcessor {

	// private static final String CSV2XML = "CSV2XML";

	private String name;

	private String delim;

	/**
	 * @param name
	 * @param delim
	 */
	public HSDS2XML(String name, String delim) {
		super();
		this.name = name;
		this.delim = delim;

	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.mdmi.core.IPreProcessor#getName()
	 */
	@Override
	public String getName() {
		return name;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.mdmi.core.IPreProcessor#canProcess(org.mdmi.MessageModel)
	 */
	@Override
	public boolean canProcess(MessageModel messageModel) {
		if (messageModel != null && messageModel.getGroup() != null) {
			if (name.equals(messageModel.getGroup().getName())) {
				return true;
			}
		}
		return false;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.mdmi.core.IPreProcessor#processMessage(org.mdmi.core.MdmiMessage)
	 */
	@Override
	public void processMessage(MessageModel messageModel, MdmiMessage message) {

		Reader inputString = new StringReader(message.getDataAsString());

		List<List<String>> records = new ArrayList<List<String>>();
		try (CSVReader csvReader = new CSVReader(inputString);) {
			records.addAll(csvReader.readAll());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		if (messageModel.getSyntaxModel() != null && messageModel.getSyntaxModel().getRoot() instanceof Bag) {
			Bag rootBag = (Bag) messageModel.getSyntaxModel().getRoot();
			if (!rootBag.getNodes().isEmpty()) {
				String root = rootBag.getLocation();
				String element = messageModel.getMessageModelName();
				// System.out.println(toXML(lines, delim, root, element));
				message.setData(toXML(records, delim, root, element).getBytes());
			}

		}

	}

	private String toXML(List<List<String>> inputLines, String delim, String root, String elementName) {

		List<String> header = inputLines.get(0);

		String output = "<" + root + ">" + System.lineSeparator() + inputLines.stream().skip(1).map(line -> {
			List<String> cells = line;

			return "<" + elementName + ">" + System.lineSeparator() +
					IntStream.range(0, cells.size()).mapToObj(
						i -> "<" + header.get(i).replaceAll(" ", "_") + ">" +
								cells.get(i).replaceAll("\"", "").replaceAll("<", "&lt;").replaceAll(">", "&gt;") +
								"</" + header.get(i).replaceAll(" ", "_") + ">").collect(
									Collectors.joining(System.lineSeparator())) +
					"</" + elementName + ">" + System.lineSeparator();
		}).collect(Collectors.joining(System.lineSeparator())).replaceAll("&", "&amp;") + System.lineSeparator() +
				"</" + root + ">";

		System.out.println("<?xml version=\"1.0\" ?>" + System.lineSeparator() + output + System.lineSeparator());
		return "<?xml version=\"1.0\" ?>" + System.lineSeparator() + output + System.lineSeparator();
	}

}
