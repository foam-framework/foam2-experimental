/**
 * @license
 * Copyright 2020 The FOAM Authors. All Rights Reserved.
 * http://www.apache.org/licenses/LICENSE-2.0
 */

foam.CLASS({
  package: 'foam.nanos.medusa',
  name: 'MedusaEntryReplayBatchDAO',
  extends: 'foam.dao.ProxyDAO',


  documentation: `Handle ReplayBatchCmd, issuing Mediator side puts()`,

  javaImports: [
    'foam.dao.BatchCmd',
    'foam.nanos.logger.PrefixLogger',
    'foam.nanos.logger.Logger',
    'java.util.List'
  ],
  
  properties: [
    {
      name: 'logger',
      class: 'FObjectProperty',
      of: 'foam.nanos.logger.Logger',
      visibility: 'HIDDEN',
      javaFactory: `
        return new PrefixLogger(new Object[] {
          this.getClass().getSimpleName()
        }, (Logger) getX().get("logger"));
      `
    }
  ],

  methods: [
    {
      name: 'cmd_',
      javaCode: `
      if ( obj instanceof BatchCmd ) {
        BatchCmd cmd = (BatchCmd) obj;
        getLogger().info("cmd", cmd.getClass().getSimpleName());
        if ( cmd instanceof ReplayBatchCmd ) {
          ReplayBatchCmd rbc = (ReplayBatchCmd) obj;
          getLogger().info("cmd", "ReplayBatchCmd", rbc.getFromIndex(), rbc.getToIndex(), rbc.getDetails().getResponder());
        }

        List<MedusaEntry> list = cmd.getBatch();
        for (MedusaEntry entry : list) {
          getDelegate().put_(x, entry);
        }
        return cmd;
      }
      return getDelegate().cmd_(x, obj);
      `
    }
  ]
});
 