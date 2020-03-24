/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.config;

import alfio.manager.*;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.user.UserManager;
import alfio.model.user.*;
import alfio.model.user.Role;
import alfio.repository.user.*;
import alfio.repository.user.join.UserOrganizationRepository;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.Claim;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.*;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.GenericFilterBean;

import javax.servlet.*;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static alfio.model.system.ConfigurationKeys.ENABLE_CAPTCHA_FOR_LOGIN;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig
{

    private static final String ADMIN_API = "/admin/api";
    private static final String ADMIN_PUBLIC_API = "/api/v1/admin";
    private static final String CSRF_SESSION_ATTRIBUTE = "CSRF_SESSION_ATTRIBUTE";
    public static final String CSRF_PARAM_NAME = "_csrf";
    public static final String OPERATOR = "OPERATOR";
    private static final String SUPERVISOR = "SUPERVISOR";
    public static final String SPONSOR = "SPONSOR";
    private static final String ADMIN = "ADMIN";
    private static final String ALFIO_ADMIN = "ALFIO_ADMIN";
    private static final String ALFIO_BACKOFFICE = "ALFIO_BACKOFFICE";
    private static final String OWNER = "OWNER";
    private static final String API_CLIENT = "API_CLIENT";
    private static final String X_REQUESTED_WITH = "X-Requested-With";

    private static class APIKeyAuthFilter extends AbstractPreAuthenticatedProcessingFilter
    {

        @Override
        protected Object getPreAuthenticatedPrincipal(HttpServletRequest request)
        {
            return isTokenAuthentication(request) ? StringUtils.trim(request.getHeader("Authorization").substring("apikey ".length())) : null;
        }

        @Override
        protected Object getPreAuthenticatedCredentials(HttpServletRequest request)
        {
            return "N/A";
        }
    }


    public static class APITokenAuthentication extends AbstractAuthenticationToken
    {

        private Object credentials;
        private final Object principal;


        public APITokenAuthentication(Object principal, Object credentials, Collection<? extends GrantedAuthority> authorities)
        {
            super(authorities);
            this.credentials = credentials;
            this.principal = principal;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials()
        {
            return credentials;
        }

        @Override
        public Object getPrincipal()
        {
            return principal;
        }
    }

    public static class WrongAccountTypeException extends AccountStatusException
    {

        public WrongAccountTypeException(String msg)
        {
            super(msg);
        }
    }

    @Bean
    public CsrfTokenRepository getCsrfTokenRepository()
    {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setSessionAttributeName(CSRF_SESSION_ATTRIBUTE);
        repository.setParameterName(CSRF_PARAM_NAME);
        return repository;
    }

    @Configuration
    @Order(0)
    public static class APITokenAuthWebSecurity extends WebSecurityConfigurerAdapter
    {

        private final UserRepository userRepository;
        private final AuthorityRepository authorityRepository;

        public APITokenAuthWebSecurity(UserRepository userRepository,
                                       AuthorityRepository authorityRepository)
        {
            this.userRepository = userRepository;
            this.authorityRepository = authorityRepository;
        }

        //https://stackoverflow.com/a/48448901
        @Override
        protected void configure(HttpSecurity http) throws Exception
        {

            APIKeyAuthFilter filter = new APIKeyAuthFilter();
            filter.setAuthenticationManager(authentication -> {
                //
                String apiKey = (String) authentication.getPrincipal();
                //check if user type ->
                User user = userRepository.findByUsername(apiKey).orElseThrow(() -> new BadCredentialsException("Api key " + apiKey + " don't exists"));
                if (!user.isEnabled())
                {
                    throw new DisabledException("Api key " + apiKey + " is disabled");
                }
                if (User.Type.API_KEY != user.getType())
                {
                    throw new WrongAccountTypeException("Wrong account type for username " + apiKey);
                }
                if (!user.isCurrentlyValid(ZonedDateTime.now(ZoneId.of("UTC"))))
                {
                    throw new DisabledException("Api key " + apiKey + " is expired");
                }

                return new APITokenAuthentication(
                    authentication.getPrincipal(),
                    authentication.getCredentials(),
                    authorityRepository.findRoles(apiKey).stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList()));
            });


            http.requestMatcher(WebSecurityConfig::isTokenAuthentication)
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and().csrf().disable()
                .authorizeRequests()
                .antMatchers(ADMIN_PUBLIC_API + "/**").hasRole(API_CLIENT)
                .antMatchers(ADMIN_API + "/check-in/**").hasAnyRole(OPERATOR, SUPERVISOR)
                .antMatchers(HttpMethod.GET, ADMIN_API + "/events").hasAnyRole(OPERATOR, SUPERVISOR, SPONSOR)
                .antMatchers(HttpMethod.GET, ADMIN_API + "/user-type", ADMIN_API + "/user/details").hasAnyRole(OPERATOR, SUPERVISOR, SPONSOR)
                .antMatchers(ADMIN_API + "/**").denyAll()
                .antMatchers(HttpMethod.POST, "/api/attendees/sponsor-scan").hasRole(SPONSOR)
                .antMatchers(HttpMethod.GET, "/api/attendees/*/ticket/*").hasAnyRole(OPERATOR, SUPERVISOR, API_CLIENT)
                .antMatchers("/**").authenticated()
                .and().addFilter(filter);
        }
    }

    private static boolean isTokenAuthentication(HttpServletRequest request)
    {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.toLowerCase(Locale.ENGLISH).startsWith("apikey ");
    }

    @Profile("oauth2")
    @Configuration
    @Order(1)
    public static class OpenIdFormBasedWebSecurity extends AbstractFormBasedWebSecurity
    {
        public OpenIdFormBasedWebSecurity(Environment environment,
                                          UserManager userManager,
                                          RecaptchaService recaptchaService,
                                          ConfigurationManager configurationManager,
                                          CsrfTokenRepository csrfTokenRepository,
                                          DataSource dataSource,
                                          PasswordEncoder passwordEncoder,
                                          OpenIdAuthenticationManager openIdAuthenticationManager,
                                          UserRepository userRepository,
                                          AuthorityRepository authorityRepository,
                                          UserOrganizationRepository userOrganizationRepository,
                                          OrganizationRepository organizationRepository)
        {
            super(environment, userManager, recaptchaService, configurationManager, csrfTokenRepository, dataSource, passwordEncoder, openIdAuthenticationManager, userRepository, authorityRepository, userOrganizationRepository, organizationRepository);
        }
    }

    /**
     * Default form based configuration.
     */
    @Profile("!oauth2")
    @Configuration
    @Order(1)
    public static class FormBasedWebSecurity extends AbstractFormBasedWebSecurity
    {
        public FormBasedWebSecurity(Environment environment,
                                    UserManager userManager,
                                    RecaptchaService recaptchaService,
                                    ConfigurationManager configurationManager,
                                    CsrfTokenRepository csrfTokenRepository,
                                    DataSource dataSource,
                                    PasswordEncoder passwordEncoder,
                                    UserRepository userRepository,
                                    AuthorityRepository authorityRepository,
                                    UserOrganizationRepository userOrganizationRepository,
                                    OrganizationRepository organizationRepository)
        {
            super(environment, userManager, recaptchaService, configurationManager, csrfTokenRepository, dataSource, passwordEncoder, null, userRepository, authorityRepository, userOrganizationRepository, organizationRepository);
        }
    }

    static abstract class AbstractFormBasedWebSecurity extends WebSecurityConfigurerAdapter
    {
        private final Environment environment;
        private final UserManager userManager;
        private final RecaptchaService recaptchaService;
        private final ConfigurationManager configurationManager;
        private final CsrfTokenRepository csrfTokenRepository;
        private final DataSource dataSource;
        private final PasswordEncoder passwordEncoder;
        private final OpenIdAuthenticationManager openIdAuthenticationManager;
        private final UserRepository userRepository;
        private final AuthorityRepository authorityRepository;
        private UserOrganizationRepository userOrganizationRepository;
        private OrganizationRepository organizationRepository;

        public AbstractFormBasedWebSecurity(Environment environment,
                                            UserManager userManager,
                                            RecaptchaService recaptchaService,
                                            ConfigurationManager configurationManager,
                                            CsrfTokenRepository csrfTokenRepository,
                                            DataSource dataSource,
                                            PasswordEncoder passwordEncoder,
                                            OpenIdAuthenticationManager openIdAuthenticationManager,
                                            UserRepository userRepository,
                                            AuthorityRepository authorityRepository,
                                            UserOrganizationRepository userOrganizationRepository,
                                            OrganizationRepository organizationRepository)
        {
            this.environment = environment;
            this.userManager = userManager;
            this.recaptchaService = recaptchaService;
            this.configurationManager = configurationManager;
            this.csrfTokenRepository = csrfTokenRepository;
            this.dataSource = dataSource;
            this.passwordEncoder = passwordEncoder;
            this.openIdAuthenticationManager = openIdAuthenticationManager;
            this.userRepository = userRepository;
            this.authorityRepository = authorityRepository;
            this.userOrganizationRepository = userOrganizationRepository;
            this.organizationRepository = organizationRepository;
        }

        @Override
        public void configure(AuthenticationManagerBuilder auth) throws Exception
        {
            auth.jdbcAuthentication().dataSource(dataSource)
                .usersByUsernameQuery("select username, password, enabled from ba_user where username = ?")
                .authoritiesByUsernameQuery("select username, role from authority where username = ?")
                .passwordEncoder(passwordEncoder);
            if(openIdAuthenticationManager != null)
            {
                auth.authenticationProvider(new OAuth2AuthenticationProvider());
            }
        }

        @Override
        protected void configure(HttpSecurity http) throws Exception
        {

            if (environment.acceptsProfiles(Profiles.of("!" + Initializer.PROFILE_DEV)))
            {
                http.requiresChannel().antMatchers("/healthz").requiresInsecure()
                    .and()
                    .requiresChannel().mvcMatchers("/**").requiresSecure();
            }

            CsrfConfigurer<HttpSecurity> configurer =
                http.exceptionHandling()
                    .accessDeniedHandler((request, response, accessDeniedException) -> {
                        if (!response.isCommitted())
                        {
                            if ("XMLHttpRequest".equals(request.getHeader(X_REQUESTED_WITH)))
                            {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            }
                            else if (!response.isCommitted())
                            {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                RequestDispatcher dispatcher = request.getRequestDispatcher("/session-expired");
                                dispatcher.forward(request, response);
                            }
                        }
                    })
                    .defaultAuthenticationEntryPointFor((request, response, ex) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED), new RequestHeaderRequestMatcher(X_REQUESTED_WITH, "XMLHttpRequest"))
                    .and()
                    .headers().cacheControl().disable()
                    .and()
                    .csrf();

            Pattern pattern = Pattern.compile("^(GET|HEAD|TRACE|OPTIONS)$");
            Predicate<HttpServletRequest> csrfWhitelistPredicate = r -> r.getRequestURI().startsWith("/api/webhook/")
                || r.getRequestURI().startsWith("/api/payment/webhook/")
                || pattern.matcher(r.getMethod()).matches();
            csrfWhitelistPredicate = csrfWhitelistPredicate.or(r -> r.getRequestURI().equals("/report-csp-violation"));
            configurer.requireCsrfProtectionMatcher(new NegatedRequestMatcher(csrfWhitelistPredicate::test));

            String[] ownershipRequired = new String[]{
                ADMIN_API + "/overridable-template",
                ADMIN_API + "/additional-services",
                ADMIN_API + "/events/*/additional-field",
                ADMIN_API + "/event/*/additional-services/",
                ADMIN_API + "/overridable-template/",
                ADMIN_API + "/events/*/promo-code",
                ADMIN_API + "/reservation/event/*/reservations/list",
                ADMIN_API + "/events/*/email/",
                ADMIN_API + "/event/*/waiting-queue/load",
                ADMIN_API + "/events/*/pending-payments",
                ADMIN_API + "/events/*/export",
                ADMIN_API + "/events/*/sponsor-scan/export",
                ADMIN_API + "/events/*/invoices/**",
                ADMIN_API + "/reservation/event/*/*/audit"
            };

            configurer.csrfTokenRepository(csrfTokenRepository)
                .and()
                .authorizeRequests()
                .antMatchers(ADMIN_API + "/configuration/**", ADMIN_API + "/users/**").hasAnyRole(ADMIN, OWNER)
                .antMatchers(ADMIN_API + "/organizations/new").hasRole(ADMIN)
                .antMatchers(ADMIN_API + "/check-in/**").hasAnyRole(ADMIN, OWNER, SUPERVISOR)
                .antMatchers(HttpMethod.GET, ownershipRequired).hasAnyRole(ADMIN, OWNER)
                .antMatchers(HttpMethod.GET, ADMIN_API + "/**").hasAnyRole(ADMIN, OWNER, SUPERVISOR)
                .antMatchers(HttpMethod.POST, ADMIN_API + "/reservation/event/*/new", ADMIN_API + "/reservation/event/*/*").hasAnyRole(ADMIN, OWNER, SUPERVISOR)
                .antMatchers(HttpMethod.PUT, ADMIN_API + "/reservation/event/*/*/notify", ADMIN_API + "/reservation/event/*/*/confirm").hasAnyRole(ADMIN, OWNER, SUPERVISOR)
                .antMatchers(ADMIN_API + "/**").hasAnyRole(ADMIN, OWNER)
                .antMatchers("/admin/**/export/**").hasAnyRole(ADMIN, OWNER)
                .antMatchers("/admin/**").hasAnyRole(ADMIN, OWNER, SUPERVISOR)
                .antMatchers("/api/attendees/**").denyAll()
                .antMatchers("/callback").permitAll()
                .antMatchers("/**").permitAll()
                .and()
                .formLogin()
                .loginPage("/authentication")
                .loginProcessingUrl("/authenticate")
                .failureUrl("/authentication?failed")
                .and().logout().permitAll();


            //
            http.addFilterBefore(new RecaptchaLoginFilter(recaptchaService, "/authenticate", "/authentication?recaptchaFailed", configurationManager), UsernamePasswordAuthenticationFilter.class);
            if(openIdAuthenticationManager != null)
            {
                http.addFilterBefore(new OpenIdCallbackLoginFilter(configurationManager, openIdAuthenticationManager, new AntPathRequestMatcher("/callback", "GET"), authenticationManager(), userRepository, authorityRepository, passwordEncoder, userManager, userOrganizationRepository, organizationRepository), UsernamePasswordAuthenticationFilter.class);
                http.addFilterBefore(new OpenIdAuthenticationFilter(configurationManager, "/authentication", openIdAuthenticationManager), RecaptchaLoginFilter.class);
            }


            //FIXME create session and set csrf cookie if we are getting a v2 public api, an admin api call , will switch to pure cookie based
            http.addFilterBefore((servletRequest, servletResponse, filterChain) -> {

                HttpServletRequest req = (HttpServletRequest) servletRequest;
                HttpServletResponse res = (HttpServletResponse) servletResponse;
                var reqUri = req.getRequestURI();

                if ((reqUri.startsWith("/api/v2/public/") || reqUri.startsWith("/admin/api/") || reqUri.startsWith("/api/v2/admin/")) && "GET".equalsIgnoreCase(req.getMethod()))
                {
                    CsrfToken csrf = csrfTokenRepository.loadToken(req);
                    if (csrf == null)
                    {
                        csrf = csrfTokenRepository.generateToken(req);
                    }
                    Cookie cookie = new Cookie("XSRF-TOKEN", csrf.getToken());
                    cookie.setPath("/");
                    res.addCookie(cookie);
                }
                filterChain.doFilter(servletRequest, servletResponse);
            }, RecaptchaLoginFilter.class);

            if (environment.acceptsProfiles(Profiles.of(Initializer.PROFILE_DEMO)))
            {
                http.addFilterAfter(new UserCreatorBeforeLoginFilter(userManager, "/authenticate"), RecaptchaLoginFilter.class);
            }
        }

        private static class OpenIdCallbackLoginFilter extends AbstractAuthenticationProcessingFilter
        {
            private final Logger logger = LoggerFactory.getLogger(OpenIdCallbackLoginFilter.class);

            private final ConfigurationManager configurationManager;
            private final RequestMatcher requestMatcher;
            private final UserRepository userRepository;
            private final AuthorityRepository authorityRepository;
            private final PasswordEncoder passwordEncoder;
            private final UserManager userManager;
            private final UserOrganizationRepository userOrganizationRepository;
            private final OrganizationRepository organizationRepository;
            private final OpenIdAuthenticationManager openIdAuthenticationManager;
            private String idToken;
            private String subject;
            private List<String> alfioGroups;
            private String email;
            private boolean isAdmin;
            private Set<Role> alfioRoles;
            private Map<String, Set<String>> alfioOrganizationAuthorizations;

            private OpenIdCallbackLoginFilter(ConfigurationManager configurationManager, OpenIdAuthenticationManager openIdAuthenticationManager,
                                              AntPathRequestMatcher requestMatcher, AuthenticationManager authenticationManager,
                                              UserRepository userRepository, AuthorityRepository authorityRepository, PasswordEncoder passwordEncoder,
                                              UserManager userManager, UserOrganizationRepository userOrganizationRepository,
                                              OrganizationRepository organizationRepository)
            {
                super(requestMatcher);
                this.userRepository = userRepository;
                this.authorityRepository = authorityRepository;
                this.passwordEncoder = passwordEncoder;
                this.userManager = userManager;
                this.userOrganizationRepository = userOrganizationRepository;
                this.organizationRepository = organizationRepository;
                this.setAuthenticationManager(authenticationManager);
                this.configurationManager = configurationManager;
                this.requestMatcher = requestMatcher;
                this.openIdAuthenticationManager = openIdAuthenticationManager;
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
            {
                HttpServletRequest req = (HttpServletRequest) request;
                HttpServletResponse res = (HttpServletResponse) response;

                if (requestMatcher.matches(req))
                {
                    clearClassState();

                    String code = req.getParameter(openIdAuthenticationManager.getCodeNameParameter());
                    if(code == null){
                        throw new IllegalArgumentException("authorization code cannot be null");
                    }

                    String claimsUrl = openIdAuthenticationManager.buildClaimsRetrieverUrl();
                    Map<String, Object> claims;
                    try
                    {
                        claims = retrieveClaims(claimsUrl, code);
                    }
                    catch (InterruptedException e)
                    {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }

                    populateFieldsFrom(claims);
                    extractAlfioRoles();

                    if (!userManager.usernameExists(email))
                    {
                        createUser();
                    }
                    updateRoles(alfioRoles, email);
                    updateOrganizations(email);

                    super.doFilter(req, res, chain);
                }

                chain.doFilter(request, response);
            }

            private void updateOrganizations(String username)
            {
                Optional<Integer> userId = userRepository.findIdByUserName(username);
                if(userId.isEmpty()){
                    String message = "Error: user not saved into the database";
                    logger.error(message);
                    throw new RuntimeException(message);
                }

                Set<Integer> databaseOrganizationIds = organizationRepository.findAllForUser(username).stream()
                    .map(org -> org.getId()).collect(Collectors.toSet());

                if(isAdmin){
                    databaseOrganizationIds.forEach(orgId -> userOrganizationRepository.removeOrganizationUserLink(userId.get(), orgId));
                    return;
                }


                Set<Integer> organizationIds = alfioOrganizationAuthorizations.keySet().stream()
                    .map(organizationRepository::findByNameOpenId)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(Objects::nonNull)
                    .map(org -> org.getId())
                    .collect(Collectors.toSet());

                databaseOrganizationIds.stream()
                    .filter(orgId -> !organizationIds.contains(orgId))
                    .forEach(orgId -> userOrganizationRepository.removeOrganizationUserLink(userId.get(), orgId));

                if(organizationIds.isEmpty()){
                    String message = "Error: The user needs to be ADMIN or to have at least one organization linked";
                    logger.error(message);
                    throw new RuntimeException(message);
                }

                organizationIds.stream().filter(orgId -> !databaseOrganizationIds.contains(orgId))
                    .forEach(orgId -> userOrganizationRepository.create(userId.get(), orgId));
            }

            private void clearClassState()
            {
                idToken = "";
                subject = "";
                alfioGroups = null;
                email = null;
                isAdmin = false;
                alfioRoles = new HashSet<>();
                alfioOrganizationAuthorizations = new HashMap<>();
            }

            private void extractAlfioRoles()
            {
                if(isAdmin) {
                    alfioRoles.add(Role.ADMIN);
                    return;
                }
                //FIXME at the moment, the authorizations are NOT based on the organizations, they are global
                alfioOrganizationAuthorizations.keySet().stream()
                    .map(org -> alfioOrganizationAuthorizations.get(org))
                    .forEach(authorizations ->
                        authorizations.stream().map(auth -> Role.fromRoleName("ROLE_" + auth))
                            .forEach(alfioRoles::add));
            }

            private void createUser()
            {
                userRepository.create(email, passwordEncoder.encode(subject), email, email, email, true, User.Type.INTERNAL, null, null);
            }

            private void populateFieldsFrom(Map<String, Object> claims)
            {
                idToken = (String) claims.get(openIdAuthenticationManager.getIdTokenNameParameter());

                Map<String, Claim> idTokenClaims = JWT.decode(idToken).getClaims();
                subject = idTokenClaims.get(openIdAuthenticationManager.getSubjectNameParameter()).asString();
                email = idTokenClaims.get(openIdAuthenticationManager.getEmailNameParameter()).asString();
                List<String> groups = idTokenClaims.get(openIdAuthenticationManager.getGroupsNameParameter()).asList(String.class);
                alfioGroups = groups.stream().filter(group -> group.startsWith("ALFIO_")).collect(Collectors.toList());

                if(alfioGroups.contains(ALFIO_ADMIN)){
                    isAdmin = true;
                }

                if(isAdmin || alfioGroups.isEmpty()){
                    return;
                }

                List<String> alfioRoles = idTokenClaims.get(openIdAuthenticationManager.getAlfioGroupsNameParameter()).asList(String.class);
                for(String alfioRole : alfioRoles){
                    String[] orgRole = alfioRole.split("/");
                    String organization = orgRole[1];
                    String role = orgRole[2];

                    if(alfioOrganizationAuthorizations.containsKey(organization)){
                        alfioOrganizationAuthorizations.get(organization).add(role);
                        continue;
                    }
                    alfioOrganizationAuthorizations.put(organization, new HashSet<>(Arrays.asList(role)));
                }
            }

            @Override
            public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) throws AuthenticationException, IOException, ServletException
            {
                List<GrantedAuthority> authorities = alfioRoles.stream().map(Role::getRoleName)
                    .map(SimpleGrantedAuthority::new).collect(Collectors.toList());
                OAuth2AlfioAuthentication authentication = new OAuth2AlfioAuthentication(authorities, idToken, subject, email, openIdAuthenticationManager.buildLogoutUrl());
                return getAuthenticationManager().authenticate(authentication);
            }

            private Map<String, Object> retrieveClaims(String claimsUrl, String code) throws IOException, InterruptedException
            {
                HttpClient client = HttpClient.newHttpClient();

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(claimsUrl))
                    .header("Content-Type", openIdAuthenticationManager.getContentType())
                    .POST(HttpRequest.BodyPublishers.ofString(openIdAuthenticationManager.buildRetrieveClaimsUrlBody(code)))
                    .build();

                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

                Map<String, Object> map = new ObjectMapper().readValue(response.body(), Map.class);
                return map;
            }

            private void updateRoles(Set<Role> roles, String username){
                authorityRepository.revokeAll(username);
                roles.forEach(role -> authorityRepository.create(username, role.getRoleName()));
            }
        }

        private static class OAuth2AuthenticationProvider implements AuthenticationProvider{
            @Override
            public Authentication authenticate(Authentication authentication) throws AuthenticationException
            {
                return authentication;
            }

            @Override
            public boolean supports(Class<?> authentication)
            {
                return authentication.equals(OAuth2AlfioAuthentication.class);
            }
        }

        private static class OpenIdAuthenticationFilter extends GenericFilterBean
        {
            private final ConfigurationManager configurationManager;
            private final RequestMatcher requestMatcher;
            private OpenIdAuthenticationManager openIdAuthenticationManager;

            private OpenIdAuthenticationFilter(ConfigurationManager configurationManager, String loginURL, OpenIdAuthenticationManager openIdAuthenticationManager)
            {
                this.configurationManager = configurationManager;
                this.requestMatcher = new AntPathRequestMatcher(loginURL, "GET");
                this.openIdAuthenticationManager = openIdAuthenticationManager;
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
            {
                HttpServletRequest req = (HttpServletRequest) request;
                HttpServletResponse res = (HttpServletResponse) response;

                if (requestMatcher.matches(req))
                {
                    if(SecurityContextHolder.getContext().getAuthentication() != null || req.getParameterMap().containsKey("logout")) {
                        res.sendRedirect("/admin/");
                        return;
                    }
                    res.sendRedirect(openIdAuthenticationManager.buildAuthorizeUrl(openIdAuthenticationManager.getScopes()));
                    return;
                }

                chain.doFilter(request, response);
            }
        }

        private static class RecaptchaLoginFilter extends GenericFilterBean
        {
            private final RequestMatcher requestMatcher;
            private final RecaptchaService recaptchaService;
            private final String recaptchaFailureUrl;
            private final ConfigurationManager configurationManager;


            RecaptchaLoginFilter(RecaptchaService recaptchaService,
                                 String loginProcessingUrl,
                                 String recaptchaFailureUrl,
                                 ConfigurationManager configurationManager)
            {
                this.requestMatcher = new AntPathRequestMatcher(loginProcessingUrl, "POST");
                this.recaptchaService = recaptchaService;
                this.recaptchaFailureUrl = recaptchaFailureUrl;
                this.configurationManager = configurationManager;
            }


            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
            {
                HttpServletRequest req = (HttpServletRequest) request;
                HttpServletResponse res = (HttpServletResponse) response;
                if (requestMatcher.matches(req) &&
                    configurationManager.getForSystem(ENABLE_CAPTCHA_FOR_LOGIN).getValueAsBooleanOrDefault(true) &&
                    !recaptchaService.checkRecaptcha(null, req))
                {
                    res.sendRedirect(recaptchaFailureUrl);
                    return;
                }

                chain.doFilter(request, response);
            }
        }

        // generate a user if it does not exists, to be used by the demo profile
        private static class UserCreatorBeforeLoginFilter extends GenericFilterBean
        {

            private final UserManager userManager;
            private final RequestMatcher requestMatcher;

            UserCreatorBeforeLoginFilter(UserManager userManager, String loginProcessingUrl)
            {
                this.userManager = userManager;
                this.requestMatcher = new AntPathRequestMatcher(loginProcessingUrl, "POST");
            }


            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
            {
                HttpServletRequest req = (HttpServletRequest) request;

                //ensure organization/user
                if (requestMatcher.matches(req) && req.getParameter("username") != null && req.getParameter("password") != null)
                {
                    String username = req.getParameter("username");
                    if (!userManager.usernameExists(username))
                    {
                        int orgId = userManager.createOrganization(username, "Demo organization", username);
                        userManager.insertUser(orgId, username, "", "", username, Role.OWNER, User.Type.DEMO, req.getParameter("password"), null, null);
                    }
                }

                chain.doFilter(request, response);
            }
        }
    }

    public static class OAuth2AlfioAuthentication extends AbstractAuthenticationToken
    {
        private final String idToken;
        private final String subject;
        private final String email;
        private final String idpLogoutRedirectionUrl;

        public OAuth2AlfioAuthentication(Collection<? extends GrantedAuthority> authorities, String idToken, String subject, String email, String idpLogoutRedirectionUrl)
        {
            super(authorities);
            this.idToken = idToken;
            this.subject = subject;
            this.email = email;
            this.idpLogoutRedirectionUrl = idpLogoutRedirectionUrl;
        }

        @Override
        public Object getCredentials()
        {
            return idToken;
        }

        @Override
        public Object getPrincipal()
        {
            return subject;
        }

        @Override
        public String getName(){
            return email;
        }

        public String getIdpLogoutRedirectionUrl(){
            return idpLogoutRedirectionUrl;
        }
    }
}
