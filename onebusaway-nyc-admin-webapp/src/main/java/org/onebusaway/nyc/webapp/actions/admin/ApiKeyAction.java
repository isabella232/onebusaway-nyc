package org.onebusaway.nyc.webapp.actions.admin;

import org.onebusaway.presentation.impl.NextActionSupport;
import org.onebusaway.users.model.UserIndex;
import org.onebusaway.users.model.UserIndexKey;
import org.onebusaway.users.services.UserIndexTypes;
import org.onebusaway.users.services.UserPropertiesService;
import org.onebusaway.users.services.UserService;
import org.springframework.beans.factory.annotation.Autowired;

import com.opensymphony.xwork2.validator.annotations.RequiredStringValidator;

/**
 * Creates API key for the user. Also authorizes the user to use API. 
 * @author abelsare
 *
 */
public class ApiKeyAction extends NextActionSupport{

	private static final long serialVersionUID = 1L;
	private String key;
	private UserService userService;
	private UserPropertiesService userPropertiesService;
	
	
	@Override
	public String execute() throws Exception {
		UserIndexKey userIndexKey = new UserIndexKey(UserIndexTypes.API_KEY, key);
	    UserIndex userIndex = userService.getOrCreateUserForIndexKey(userIndexKey,
	        key, false);
	    userPropertiesService.authorizeApi(userIndex.getUser(), 0);
	    addActionMessage("Key '" +key + "' created successfully");
		return SUCCESS;
	}
	
	/**
	 * Returns the key of the user being created
	 * @return the key
	 */
	@RequiredStringValidator(message="Key is required")
	public String getKey() {
		return key;
	}
	
	/**
	 * Injects the key of the user being created
	 * @param key the key to set
	 */
	public void setKey(String key) {
		this.key = key;
	}

	/**
	 * Injects {@link UserService}
	 * @param userService the userService to set
	 */
	@Autowired
	public void setUserService(UserService userService) {
		this.userService = userService;
	}

	/**
	 * Injects {@link UserPropertiesService}
	 * @param userPropertiesService the userPropertiesService to set
	 */
	@Autowired
	public void setUserPropertiesService(UserPropertiesService userPropertiesService) {
		this.userPropertiesService = userPropertiesService;
	}
	

}