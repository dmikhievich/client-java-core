/*
 * Copyright 2016 EPAM Systems
 *
 *
 * This file is part of EPAM Report Portal.
 * https://github.com/epam/ReportPortal
 *
 * Report Portal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Report Portal is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Report Portal.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.epam.reportportal.listeners.cucumber;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.epam.reportportal.listeners.Statuses;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ.File;
import com.google.common.io.ByteSource;

import gherkin.formatter.Formatter;
import gherkin.formatter.Reporter;
import gherkin.formatter.model.*;

/**
 * Abstract Cucumber formatter/reporter for Report Portal
 * 
 * @author Sergey_Gvozdyukevich
 * 
 */
public abstract class AbstractReporter implements Formatter, Reporter {
	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractReporter.class);

	protected static final String COLON_INFIX = ": ";

	protected static class ScenarioModel {
		private String id;
		private Queue<Step> steps;
		private String status;
		private StringBuilder issueComments;

		public ScenarioModel(String newId) {
			id = newId;
			steps = new ArrayDeque<Step>();
			status = Statuses.PASSED;
			issueComments = new StringBuilder();
		}

		public String getId() {
			return id;
		}

		public void addStep(Step step) {
			steps.add(step);
		}

		public Step getNextStep() {
			return steps.poll();
		}

		public boolean noMoreSteps() {
			return steps.isEmpty();
		}

		public void updateStatus(String newStatus) {
			if (!newStatus.equals(status)) {
				if (Statuses.FAILED.equals(status) || Statuses.FAILED.equals(newStatus)) {
					status = Statuses.FAILED;
				} else {
					status = Statuses.SKIPPED;
				}
			}
		}

		public String getStatus() {
			return status;
		}

		public void appendIssue(String issue) {
			issueComments.append("\n").append(issue);
		}

		public String getIssueComments() {
			return issueComments.toString();
		}
	}

	/* formatter context */
	protected String currentLaunchId;
	protected String currentFeatureUri;
	protected String currentFeatureId;
	protected ScenarioModel currentScenario;
	protected String stepPrefix;

	private Queue<String> outlineIterations;
	private Boolean inBackground;

	protected AbstractReporter() {
		currentLaunchId = null;
		currentFeatureUri = null;
		currentFeatureId = null;
		currentScenario = null;
		outlineIterations = new ArrayDeque<String>();
		stepPrefix = "";

		inBackground = false;
	}

	/**
	 * Start RP launch
	 */
	protected void beforeLaunch() {
		currentLaunchId = Utils.startLaunch();
	}

	/**
	 * Finish RP launch
	 */
	protected void afterLaunch() {
		Utils.finishLaunch(currentLaunchId);
		currentLaunchId = null;
	}

	// TODO will not be needed in RP 2.0
	protected abstract void startRootItem();

	// TODO will not be needed in RP 2.0
	protected abstract void finishRootItem();

	// TODO will not be needed in RP 2.0
	protected abstract String getRootItemId();

	/**
	 * Start Cucumber feature
	 * 
	 * @param feature
	 */
	protected void beforeFeature(Feature feature) {
		currentFeatureId = Utils.startNonLeafNode(currentLaunchId, getRootItemId(),
				Utils.buildStatementName(feature, null, AbstractReporter.COLON_INFIX, null), currentFeatureUri, feature.getTags(),
				getFeatureTestItemType());
	}

	/**
	 * Finish Cucumber feature
	 */
	protected void afterFeature() {
		Utils.finishTestItem(currentFeatureId);
		currentFeatureId = null;
	}

	/**
	 * Start Cucumber scenario
	 * 
	 * @param scenario
	 * @param outlineIteration
	 *            - suffix to append to scenario name, can be null
	 */
	protected void beforeScenario(Scenario scenario, String outlineIteration) {
		String id = Utils.startNonLeafNode(currentLaunchId, currentFeatureId,
				Utils.buildStatementName(scenario, null, AbstractReporter.COLON_INFIX, outlineIteration),
				currentFeatureUri + ":" + scenario.getLine(), scenario.getTags(), getScenarioTestItemType());
		currentScenario = new ScenarioModel(id);
	}

	/**
	 * Finish Cucumber scenario
	 */
	protected void afterScenario() {
		Utils.finishTestItem(currentScenario.getId(), currentScenario.getStatus(), currentScenario.getIssueComments());
		currentScenario = null;
	}

	/**
	 * Start Cucumber step
	 * 
	 * @param step
	 */
	protected abstract void beforeStep(Step step);

	/**
	 * Finish Cucumber step
	 * 
	 * @param result
	 */
	protected abstract void afterStep(Result result);

	/**
	 * Called when before/after-hooks are started
	 * 
	 * @param isBefore
	 *            - if true, before-hook is started, if false - after-hook
	 */
	protected abstract void beforeHooks(Boolean isBefore);

	/**
	 * Called when before/after-hooks are finished
	 * 
	 * @param isBefore
	 *            - if true, before-hook is finished, if false - after-hook
	 */
	protected abstract void afterHooks(Boolean isBefore);

	/**
	 * Called when a specific before/after-hook is finished
	 * 
	 * @param match
	 * @param result
	 * @param isBefore
	 *            - if true, before-hook, if false - after-hook
	 */
	protected abstract void hookFinished(Match match, Result result, Boolean isBefore);

	/**
	 * Report test item result and error (if present)
	 * 
	 * @param result
	 *            - Cucumber result object
	 * @param message
	 *            - optional message to be logged in addition
	 */
	protected void reportResult(Result result, String message) {
		String cukesStatus = result.getStatus();
		String level = Utils.mapLevel(cukesStatus);
		String errorMessage = result.getErrorMessage();
		if (errorMessage != null) {
			Utils.sendLog(getLogDestination(), errorMessage, level, null);
		}

		if (message != null) {
			Utils.sendLog(getLogDestination(), message, level, null);
		}

		if (currentScenario != null) {
			currentScenario.updateStatus(Utils.mapStatus(result.getStatus()));
		}
	}

	/**
	 * Return current test item for logging
	 * 
	 * @return item id
	 */
	protected abstract String getLogDestination();

	/**
	 * Return RP test item name mapped to Cucumber feature
	 * 
	 * @return test item name
	 */
	protected abstract String getFeatureTestItemType();

	/**
	 * Return RP test item name mapped to Cucumber scenario
	 * 
	 * @return test item name
	 */
	protected abstract String getScenarioTestItemType();

	/********************************
	 * Cucumber interfaces implementations
	 ********************************/
	@Override
	public void before(Match match, Result result) {
		hookFinished(match, result, true);
	}

	@Override
	public void result(Result result) {
		afterStep(result);
		if (!inBackground && currentScenario.noMoreSteps()) {
			beforeHooks(false);
		}
	}

	@Override
	public void after(Match match, Result result) {
		hookFinished(match, result, false);
	}

	@Override
	public void match(Match match) {
		beforeStep(currentScenario.getNextStep());
	}

	@Override
	public void embedding(String mimeType, byte[] data) {
		File file = new File();
		String embeddingName;
		try {
			embeddingName = MimeTypes.getDefaultMimeTypes().forName(mimeType).getType().getType();
		} catch (MimeTypeException e) {
			LOGGER.warn("Mime-type not found", e);
			embeddingName = "embedding";
		}

		file.setName(embeddingName);
		file.setContent(ByteSource.wrap(data));

		Utils.sendLog(getLogDestination(), embeddingName, "UNKNOWN", file);
	}

	@Override
	public void write(String text) {
		Utils.sendLog(getLogDestination(), text, "INFO", null);
	}

	@Override
	public void syntaxError(String state, String event, List<String> legalEvents, String uri, Integer line) {
		// I have no idea when this is called
	}

	@Override
	public void uri(String uri) {
		currentFeatureUri = uri;
	}

	@Override
	public void feature(Feature feature) {
		if (currentLaunchId == null) {
			beforeLaunch();
			startRootItem();
		}
		beforeFeature(feature);
	}

	@Override
	public void scenarioOutline(ScenarioOutline scenarioOutline) {
		// noop
	}

	@Override
	public void examples(Examples examples) {
		int num = examples.getRows().size();
		// examples always have headers; therefore up to num - 1
		for (int i = 1; i < num; i++) {
			outlineIterations.add(" [" + i + "]");
		}
	}

	@Override
	public void startOfScenarioLifeCycle(Scenario scenario) {
		inBackground = false;
		beforeScenario(scenario, outlineIterations.poll());
		beforeHooks(true);
	}

	@Override
	public void background(Background background) {
		afterHooks(true);
		inBackground = true;
		stepPrefix = background.getKeyword().toUpperCase() + AbstractReporter.COLON_INFIX;
	}

	@Override
	public void scenario(Scenario scenario) {
		if (!inBackground) { // if there was no background
			afterHooks(true);
		} else {
			inBackground = false;
		}
		stepPrefix = "";
	}

	@Override
	public void step(Step step) {
		if (currentScenario != null) {
			currentScenario.addStep(step);
		}
		// otherwise it's a step collection in an outline, useless.
	}

	@Override
	public void endOfScenarioLifeCycle(Scenario scenario) {
		afterHooks(false);
		afterScenario();
	}

	@Override
	public void done() {
		// noop
	}

	@Override
	public void close() {
		if (currentLaunchId != null) {
			finishRootItem();
			afterLaunch();
		}
	}

	@Override
	public void eof() {
		afterFeature();
	}
}
