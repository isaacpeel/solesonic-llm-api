package com.solesonic.izzybot.model.atlassian.confluence;

public class Version{
	private int number;
	private String createdAt;
	private boolean minorEdit;
	private String message;
	private String authorId;

	public void setNumber(int number){
		this.number = number;
	}

	public int getNumber(){
		return number;
	}

	public void setCreatedAt(String createdAt){
		this.createdAt = createdAt;
	}

	public String getCreatedAt(){
		return createdAt;
	}

	public void setMinorEdit(boolean minorEdit){
		this.minorEdit = minorEdit;
	}

	public boolean isMinorEdit(){
		return minorEdit;
	}

	public void setMessage(String message){
		this.message = message;
	}

	public String getMessage(){
		return message;
	}

	public void setAuthorId(String authorId){
		this.authorId = authorId;
	}

	public String getAuthorId(){
		return authorId;
	}
}
