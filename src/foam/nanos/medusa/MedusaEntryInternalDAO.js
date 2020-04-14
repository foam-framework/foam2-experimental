/**
 * @license
 * Copyright 2020 The FOAM Authors. All Rights Reserved.
 * http://www.apache.org/licenses/LICENSE-2.0
 */

foam.CLASS({
  package: 'foam.nanos.medusa',
  name: 'MedusaEntryInternalDAO',
  extends: 'foam.dao.ProxyDAO',

  documentation: `Manage access to internal MedusaEntry DAO.
Presently we have data loss when both the local and non-local MedusaEntry
DAO stacks both end at x.get("internalMedusaEntryDAO").`,
  
  javaImports: [
    'foam.dao.DAO',
    'foam.nanos.logger.PrefixLogger',
    'foam.nanos.logger.Logger',
  ],
  
  properties: [
    {
      name: 'dao',
      class: 'foam.dao.DAOProperty',
      javaFactory: `
      DAO dao = (DAO) getX().get("internalMedusaEntryDAO");
      setDelegate(dao);
      return dao;
      `
    },
    {
      class: 'FObjectProperty',
      of: 'foam.nanos.logger.Logger',
      name: 'logger',
      visibility: 'HIDDEN',
      javaFactory: `
        return new PrefixLogger(new Object[] {
          this.getClass().getSimpleName()
        }, (Logger) getX().get("logger"));
      `
    },
  ],
  
  methods: [
    // {
    //   name: 'getDelegate',
    //   type: 'foam.dao.DAO',
    //   javaCode: `
    //   DAO dao = super.getDelegate();
    //   if ( dao == null ) {
    //     dao = (DAO) getX().get("internalMedusaEntryDAO");
    //     setDelegate(dao);
    //   }
    //   return dao;
    //   `
    // },
    {
      name: 'put_',
      javaCode: `
      MedusaEntry entry = (MedusaEntry) obj;
      getLogger().debug("put", entry.getIndex(), entry.getId());
      return getDao().put_(x, entry);
      `
    },
    {
      name: 'remove_',
      javaCode: `
      MedusaEntry entry = (MedusaEntry) getDao().remove_(x, obj);
      getLogger().debug("remove", entry.getId(), entry.getIndex());
      return entry;
    `
    }
  ]
});