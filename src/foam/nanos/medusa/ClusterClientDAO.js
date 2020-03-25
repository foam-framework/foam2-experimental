/**
 * @license
 * Copyright 2019 The FOAM Authors. All Rights Reserved.
 * http://www.apache.org/licenses/LICENSE-2.0
 */

foam.CLASS({
  package: 'foam.nanos.medusa',
  name: 'ClusterClientDAO',
  extends: 'foam.dao.ProxyDAO',

  documentation: 'Marshall put and remove operations to the ClusterServer.',

  javaImports: [
    'foam.core.ContextAware',
    'foam.core.FObject',
    'foam.core.X',
    'foam.dao.DAO',
    'foam.nanos.logger.Logger',
    'foam.nanos.logger.PrefixLogger'
  ],

  properties: [
    {
      documentation: `nSpec service name at the remote node.`,
      name: 'serviceName',
      class: 'String'
    },
    {
      name: 'maxRetryAttempts',
      class: 'Int',
      documentation: 'Set to -1 to infinitely retry.',
      value: 20
    },
    {
      class: 'Int',
      name: 'maxRetryDelay',
      value: 20000
    }
  ],

  methods: [
    {
      name: 'put_',
      javaCode: `
      Logger logger = new PrefixLogger(new Object[] {
        this.getClass().getSimpleName(),
        getServiceName(),
        "put_",
      }, (Logger) x.get("logger"));

      ElectoralService electoralService = (ElectoralService) x.get("electoralService");
      ClusterConfigService service = (ClusterConfigService) x.get("clusterConfigService");

      foam.core.FObject old = getDelegate().find_(x, obj.getProperty("id"));
      foam.lib.json.Outputter outputter = new foam.lib.json.Outputter(x).setPropertyPredicate(new foam.lib.ClusterPropertyPredicate());
      // Clear context so it's not marshalled across the network
      ((ContextAware) obj).setX(null);

        //TODO: outputDelta has problem when output array. Fix bugs then use output delta.
        // String record = ( old != null ) ?
        //   outputter.stringifyDelta(old, obj) :
        //   outputter.stringify(obj);
        String record = outputter.stringify(obj);
        if ( foam.util.SafetyUtil.isEmpty(record) ||
            "{}".equals(record.trim()) ) {
          logger.debug("no changes", record);
          return obj;
        }

        int retryAttempt = 0;
        int retryDelay = 10;

        ClusterCommand cmd = new ClusterCommand(x, getServiceName(), ClusterCommand.PUT, record);
        // NOTE: set context to null after init so it's not marshalled across network
        cmd.setX(null);

        while ( service != null &&
                ! service.getIsPrimary() ) {
        try {
          if ( electoralService.getState() == ElectoralServiceState.IN_SESSION ) {
              logger.debug("to primary", service.getPrimaryConfigId(), cmd);
              FObject result = (FObject) service.getPrimaryDAO(x, getServiceName()).cmd_(x, cmd);
              logger.debug("from primary", result);
              obj = obj.copyFrom(result);
              logger.debug("obj after copyFrom", obj);
              return obj;
            } else {
              logger.debug("Election in progress.", electoralService.getState().getLabel());
              throw new RuntimeException("Election in progress.");
            }
          } catch ( Throwable t ) {
            logger.debug("from primary, error", t.getMessage());

            if ( getMaxRetryAttempts() > -1 &&
                 retryAttempt >= getMaxRetryAttempts() ) {
              logger.debug("retryAttempt >= maxRetryAttempts", retryAttempt, getMaxRetryAttempts());

              if ( electoralService.getState() == ElectoralServiceState.IN_SESSION ||
                  electoralService.getState() == ElectoralServiceState.ADJOURNED ) {
                electoralService.dissolve(x);
              }
              throw t;
            }
            retryAttempt += 1;

            // delay
            try {
              if ( electoralService.getState() == ElectoralServiceState.IN_SESSION ||
                  electoralService.getState() == ElectoralServiceState.ADJOURNED ) {
                retryDelay *= 2;
              } else {
                retryDelay = 1000;
              }
              if ( retryDelay > getMaxRetryDelay() ) {
                retryDelay = 10;
              }
              logger.debug("retry attempt", retryAttempt, "delay", retryDelay);
              Thread.sleep(retryDelay);
           } catch(InterruptedException e) {
              Thread.currentThread().interrupt();
              logger.debug("InterruptedException");
              throw t;
            }
          }
        }
        if ( service != null ) {
          logger.debug("primary delegating");
          return getDelegate().put_(x, obj);
        } else {
          // service is null.
          logger.warning("ClusterConfigService not found, operation discarded.", obj);
          throw new RuntimeException("ClusterConfigService not found, operation discarded.");
        }
      `
    },
    {
      // TODO: refactor  like put.
      name: 'remove_',
      javaCode: `
      Logger logger = new PrefixLogger(new Object[] {
        this.getClass().getSimpleName(),
        getServiceName(),
        "remove_",
      }, (Logger) x.get("logger"));

      // TODO: Add Retry

      ClusterConfigService service = (ClusterConfigService) x.get("clusterConfigService");
      if ( service != null &&
           service.getConfigId() != null &&
           ! service.getIsPrimary() ) {

        foam.lib.json.Outputter outputter = new foam.lib.json.Outputter(x).setPropertyPredicate(new foam.lib.ClusterPropertyPredicate());
        String record = outputter.stringify(obj);

        ClusterCommand cmd = new ClusterCommand(x, getServiceName(), ClusterCommand.REMOVE, record);
        logger.debug("to primary", cmd);
        FObject result = (FObject) service.getPrimaryDAO(x, getServiceName()).cmd_(x, cmd);
        logger.debug("from primary", result.getClass().getSimpleName(), result);
        obj = obj.copyFrom(result);
        logger.debug("obj after copyFrom", obj);
        return obj;
      } else {
        return getDelegate().remove_(x, obj);
      }
      `
    }
  ]
});