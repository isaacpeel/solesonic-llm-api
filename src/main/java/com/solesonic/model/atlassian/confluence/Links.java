package com.solesonic.model.atlassian.confluence;

public class Links{
	private String base;
	private String editui;
	private String webui;
	private String edituiv2;
	private String tinyui;

	public void setBase(String base){
		this.base = base;
	}

	public String getBase(){
		return base;
	}

	public void setEditui(String editui){
		this.editui = editui;
	}

	public String getEditui(){
		return editui;
	}

	public void setWebui(String webui){
		this.webui = webui;
	}

	public String getWebui(){
		return webui;
	}

    @SuppressWarnings("unused")
	public void setEdituiv2(String edituiv2){
		this.edituiv2 = edituiv2;
	}

    @SuppressWarnings("unused")
	public String getEdituiv2(){
		return edituiv2;
	}

	public void setTinyui(String tinyui){
		this.tinyui = tinyui;
	}

	public String getTinyui(){
		return tinyui;
	}
}
