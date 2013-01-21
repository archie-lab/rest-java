package com.incept5.rest.user.service.impl;


import com.incept5.rest.service.exception.ValidationException;
import com.incept5.rest.user.api.CreateUserRequest;
import com.incept5.rest.user.api.ExternalUser;
import com.incept5.rest.user.api.LoginRequest;
import com.incept5.rest.user.api.UpdateUserRequest;
import com.incept5.rest.user.domain.Role;
import com.incept5.rest.user.domain.User;
import com.incept5.rest.user.exception.AuthenticationException;
import com.incept5.rest.user.exception.AuthorizationException;
import com.incept5.rest.user.exception.DuplicateUserException;
import com.incept5.rest.user.service.UserService;
import com.incept5.rest.user.social.JpaUsersConnectionRepository;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.social.connect.Connection;
import org.springframework.social.connect.UserProfile;
import org.springframework.social.connect.UsersConnectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.List;

/**
 * Service for managing User accounts
 *
 * @author: Iain Porter
 */
@Service("userService")
public class UserServiceImpl extends BaseUserServiceImpl implements UserService {

    /**
     * For Social API handling
     */
    private final UsersConnectionRepository jpaUsersConnectionRepository;

    @Autowired
    public UserServiceImpl(UsersConnectionRepository usersConnectionRepository) {
        this.jpaUsersConnectionRepository = usersConnectionRepository;
        ((JpaUsersConnectionRepository)this.jpaUsersConnectionRepository).setUserService(this);
    }

    private static final Logger LOG = LoggerFactory.getLogger(UserServiceImpl.class);

    /**
     * {@inheritDoc}
     *
     * This method creates a User with the given Role. A check is made to see if the username already exists and a duplication
     * check is made on the email address if it is present in the request.
     * <P></P>
     * The password is hashed and a SessionToken generated for subsequent authorization of role-protected requests.
     *
     */
    @Transactional
    public ExternalUser createUser(CreateUserRequest request, Role role) {
        if (!request.validate()) {
            throw new ValidationException("The CreateUserRequest was invalid");
        }
        User searchedForUser = userRepository.findByEmailAddress(request.getUser().getEmailAddress());
        if (searchedForUser != null) {
            throw new DuplicateUserException();
        }

        User savedUser = createNewUser(request, role);
        return new ExternalUser(savedUser, savedUser.getSessions().first());
    }

    @Transactional
    public ExternalUser createUser(Role role) {
        User user = new User();
        user.setRole(role);
        userRepository.save(user);
        return new ExternalUser(user, user.getSessions().first());
    }

    /**
     * {@inheritDoc}
     *
     *  Login supports authentication against a username or email attribute.
     *  If a User is retrieved that matches against one of those attributes the password in the request is hashed
     *  and compared to the persisted password for the User account.
     */
    @Transactional
    public ExternalUser login(LoginRequest request) {
        if (!request.validate()) {
            throw new ValidationException();
        }
        User user = null;
        user = userRepository.findByEmailAddress(request.getUsername());
        if (user == null) {
            throw new AuthenticationException();
        }
        if (user.hashPassword(request.getPassword()).equals(user.getHashedPassword())) {
            user.addSessionToken();
            return new ExternalUser(user, user.getSessions().first());
        } else {
            throw new AuthenticationException();
        }
    }

    /**
     * {@inheritDoc}
     *
     * Associate a Connection with a User account. If one does not exist a new User is created and linked to the
     * {@link com.incept5.rest.user.domain.SocialUser} represented in the Connection details.
     *
     * <P></P>
     *
     * A SessionToken is generated and any Profile data that can be collected from the Social account is propagated to the User object.
     *
     */
    @Transactional
    public ExternalUser socialLogin(Connection<?> connection) {

        List<String> userUuids = jpaUsersConnectionRepository.findUserIdsWithConnection(connection);
        if(userUuids.size() == 0) {
            throw new AuthenticationException();
        }
        User user = userRepository.findByUuid(userUuids.get(0)); //take the first one if there are multiple userIds for this provider Connection
        if (user == null) {
            throw new AuthenticationException();
        }
        updateUserFromProfile(connection, user);
        user.addSessionToken();
        return new ExternalUser(user, user.getSessions().first());

    }

    /**
     * Allow user to get their own profile or a user with administrator role to get any profile
     *
     * @param requestingUser
     * @param userIdentifier
     * @return user
     */
    @Transactional
    public ExternalUser getUser(ExternalUser requestingUser, String userIdentifier) {
        Assert.notNull(requestingUser);
        Assert.notNull(userIdentifier);
        User user = ensureUserIsLoaded(userIdentifier);
        if(!requestingUser.getId().equals(user.getUuid().toString()) && !requestingUser.getRole().equalsIgnoreCase(Role.administrator.toString()))  {
           throw new AuthorizationException("User not authorized to load profile");
        }
        return new ExternalUser(user);
    }


    @Transactional
    public void deleteUser(ExternalUser userMakingRequest, String userId) {
        Assert.notNull(userMakingRequest);
        Assert.notNull(userId);
        User userToDelete = ensureUserIsLoaded(userId);
        if (userMakingRequest.getRole().equalsIgnoreCase(Role.administrator.toString()) && (userToDelete.hasRole(Role.anonymous) || userToDelete.hasRole(Role.authenticated))) {
            userRepository.delete(userToDelete);
        } else {
            throw new AuthorizationException("User cannot be deleted. Only users with anonymous or authenticated role can be deleted.");
        }
    }

    @Transactional
    public ExternalUser saveUser(String userId, UpdateUserRequest request) {
        if (!request.validate()) {
            throw new ValidationException();
        }
        User user = ensureUserIsLoaded(userId);
        if(request.getFirstName() != null) {
            user.setFirstName(request.getFirstName());
        }
        if(request.getLastName() != null) {
            user.setLastName(request.getLastName());
        }
        if(request.getEmailAddress() != null) {
            if(!request.getEmailAddress().equals(user.getEmailAddress())) {
                user.setEmailAddress(request.getEmailAddress());
                user.setVerified(false);
            }
        }
        userRepository.save(user);
        return new ExternalUser(user);
    }

    @Transactional
    public Integer deleteExpiredSessions(int timeSinceLastUpdatedInMinutes) {
        DateTime date = new DateTime();
        date = date.minusMinutes(timeSinceLastUpdatedInMinutes);
        List<User> expiredUserSessions = userRepository.findByExpiredSession(date.toDate());
        int count = expiredUserSessions.size();
        for(User user : expiredUserSessions) {
            user.removeExpiredSessions(date.toDate());
        }
        if(count > 0) {
            userRepository.save(expiredUserSessions);
        }
        return count;
    }

    public void saveUserSession(ExternalUser externalUser) {
        User user = ensureUserIsLoaded(externalUser.getId());
        user.setActiveSession(externalUser.getActiveSession());
        userRepository.save(user);
    }

    private User createNewUser(CreateUserRequest request, Role role) {
        User userToSave = new User(request.getUser());
        userToSave.setHashedPassword(userToSave.hashPassword(request.getPassword().getPassword()));
        userToSave.setRole(role);
        return userRepository.save(userToSave);
    }

    private void updateUserFromProfile(Connection<?> connection, User user) {
        UserProfile profile = connection.fetchUserProfile();
        user.setEmailAddress(profile.getEmail());
        user.setFirstName(profile.getFirstName());
        user.setLastName(profile.getLastName());
        //users logging in from social network are already verified
        user.setVerified(true);
        if(user.hasRole(Role.anonymous)) {
            user.setRole(Role.authenticated);
        }
        userRepository.save(user);
    }

}
