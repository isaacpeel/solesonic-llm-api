package com.solesonic.model.atlassian.confluence;

public class Page {
	private Links links;
	private Integer lastOwnerId;
	private String ownerId;
	private String authorId;
	private Body body;
	private String title;
	private Version version;
	private String parentType;
	private Integer parentId;
	private String createdAt;
	private String spaceId;
	private int position;
	private String id;
	private String status;

	public void setLinks(Links links){
		this.links = links;
	}

	public Links getLinks(){
		return links;
	}

	public void setLastOwnerId(Integer lastOwnerId){
		this.lastOwnerId = lastOwnerId;
	}

	public Object getLastOwnerId(){
		return lastOwnerId;
	}

	public void setOwnerId(String ownerId){
		this.ownerId = ownerId;
	}

	public String getOwnerId(){
		return ownerId;
	}

	public void setAuthorId(String authorId){
		this.authorId = authorId;
	}

	public String getAuthorId(){
		return authorId;
	}

	public void setBody(Body body){
		this.body = body;
	}

	public Body getBody(){
		return body;
	}

	public void setTitle(String title){
		this.title = title;
	}

	public String getTitle(){
		return title;
	}

	public void setVersion(Version version){
		this.version = version;
	}

	public Version getVersion(){
		return version;
	}

	public void setParentType(String parentType){
		this.parentType = parentType;
	}

	public Object getParentType(){
		return parentType;
	}

	public void setParentId(Integer parentId){
		this.parentId = parentId;
	}

	public Object getParentId(){
		return parentId;
	}

	public void setCreatedAt(String createdAt){
		this.createdAt = createdAt;
	}

	public String getCreatedAt(){
		return createdAt;
	}

	public void setSpaceId(String spaceId){
		this.spaceId = spaceId;
	}

	public String getSpaceId(){
		return spaceId;
	}

	public void setPosition(int position){
		this.position = position;
	}

	public int getPosition(){
		return position;
	}

	public void setId(String id){
		this.id = id;
	}

	public String getId(){
		return id;
	}

	public void setStatus(String status){
		this.status = status;
	}

	public String getStatus(){
		return status;
	}
}
