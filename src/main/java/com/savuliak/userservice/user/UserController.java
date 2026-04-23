package com.savuliak.userservice.user;

import com.savuliak.userservice.security.AuthenticatedUser;
import com.savuliak.userservice.security.CurrentUser;
import com.savuliak.userservice.user.dto.PublicUserResponse;
import com.savuliak.userservice.user.dto.UpdateUserRequest;
import com.savuliak.userservice.user.dto.UserResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserResponse getMe(@CurrentUser AuthenticatedUser user) {
        return userService.getMe(user);
    }

    @PatchMapping("/me")
    public UserResponse updateMe(@CurrentUser AuthenticatedUser user,
                                 @RequestBody UpdateUserRequest request) {
        return userService.updateMe(user, request);
    }

    @GetMapping("/{id}/public")
    public PublicUserResponse getPublic(@PathVariable UUID id) {
        return userService.getPublic(id);
    }
}
