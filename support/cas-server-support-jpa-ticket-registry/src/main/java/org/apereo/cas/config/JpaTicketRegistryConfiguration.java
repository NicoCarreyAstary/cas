package org.apereo.cas.config;

import org.apereo.cas.CentralAuthenticationService;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.configuration.model.support.jpa.JpaConfigDataHolder;
import org.apereo.cas.configuration.support.Beans;
import org.apereo.cas.configuration.support.JpaBeans;
import org.apereo.cas.ticket.AbstractTicket;
import org.apereo.cas.ticket.TicketCatalog;
import org.apereo.cas.ticket.registry.JpaTicketRegistry;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.apereo.cas.ticket.registry.support.JpaLockingStrategy;
import org.apereo.cas.ticket.registry.support.LockingStrategy;
import org.apereo.cas.util.CoreTicketUtils;
import org.apereo.cas.util.InetAddressUtils;

import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This this {@link JpaTicketRegistryConfiguration}.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
@Configuration("jpaTicketRegistryConfiguration")
@EnableConfigurationProperties(CasConfigurationProperties.class)
@EnableTransactionManagement(proxyTargetClass = true)
public class JpaTicketRegistryConfiguration {

    @Autowired
    private CasConfigurationProperties casProperties;

    @Bean
    public List<String> ticketPackagesToScan() {
        val reflections =
            new Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage(CentralAuthenticationService.NAMESPACE))
                .setScanners(new SubTypesScanner(false)));
        val subTypes = reflections.getSubTypesOf(AbstractTicket.class);
        return subTypes
            .stream()
            .map(t -> t.getPackage().getName())
            .collect(Collectors.<String>toList());
    }

    @Lazy
    @Bean
    public LocalContainerEntityManagerFactoryBean ticketEntityManagerFactory() {
        return JpaBeans.newHibernateEntityManagerFactoryBean(
            new JpaConfigDataHolder(
                JpaBeans.newHibernateJpaVendorAdapter(casProperties.getJdbc()),
                "jpaTicketRegistryContext",
                ticketPackagesToScan(),
                dataSourceTicket()),
            casProperties.getTicket().getRegistry().getJpa());
    }

    @Bean
    public PlatformTransactionManager ticketTransactionManager(@Qualifier("ticketEntityManagerFactory") final EntityManagerFactory emf) {
        val mgmr = new JpaTransactionManager();
        mgmr.setEntityManagerFactory(emf);
        return mgmr;
    }

    @Bean
    @ConditionalOnMissingBean(name = "dataSourceTicket")
    public DataSource dataSourceTicket() {
        return JpaBeans.newDataSource(casProperties.getTicket().getRegistry().getJpa());
    }

    @Autowired
    @Bean
    @RefreshScope
    public TicketRegistry ticketRegistry(@Qualifier("ticketCatalog") final TicketCatalog ticketCatalog) {
        val jpa = casProperties.getTicket().getRegistry().getJpa();
        val bean = new JpaTicketRegistry(jpa.getTicketLockType(), ticketCatalog);
        bean.setCipherExecutor(CoreTicketUtils.newTicketRegistryCipherExecutor(jpa.getCrypto(), "jpa"));
        return bean;
    }

    @Bean
    public LockingStrategy lockingStrategy() {
        val registry = casProperties.getTicket().getRegistry();
        val uniqueId = StringUtils.defaultIfEmpty(casProperties.getHost().getName(), InetAddressUtils.getCasServerHostName());
        return new JpaLockingStrategy("cas-ticket-registry-cleaner", uniqueId,
            Beans.newDuration(registry.getJpa().getJpaLockingTimeout()).getSeconds());
    }
}
