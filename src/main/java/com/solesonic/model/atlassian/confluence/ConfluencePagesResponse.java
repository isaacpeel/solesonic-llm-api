package com.solesonic.model.atlassian.confluence;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class ConfluencePagesResponse {
	@JsonProperty("_links")
	private ResponseLinks links;
	private List<Page> results;

	public void setLinks(ResponseLinks links){
		this.links = links;
	}

	public ResponseLinks getLinks(){
		return links;
	}

	public void setResults(List<Page> results){
		this.results = results;
	}

	public List<Page> getResults(){
		return results;
	}
}