foam.CLASS({
  package: 'foam.u2',
  name: 'ViewSpecWithJava',
  extends: 'foam.u2.ViewSpec',
  properties: [
    ['view', { class: 'foam.u2.view.MapView' }],
    ['type', 'foam.lib.json.UnknownFObject'],
    ['javaInfoType', 'foam.core.AbstractFObjectPropertyInfo'],
    ['javaJSONParser', 'new foam.lib.json.UnknownFObjectParser()'],
    // TODO: remove next line when permanently fixed in ViewSpec
    ['fromJSON', function fromJSON(value, ctx, prop, json) {
      return value;
    }]
  ]
});

foam.CLASS({
  package: 'foam.comics.v2',
  name: 'NamedView',
  properties: [
    {
      class: 'String',
      name: 'name'
    },
    {
      class: 'foam.u2.ViewSpecWithJava',
      name: 'view'
    },
    {
      class: 'String',
      name: 'icon'
    }
  ]
}); 

foam.CLASS({
  package: 'foam.comics.v2',
  name: 'CannedQuery',
  properties: [
    {
      class: 'String',
      name: 'name',
      expression: function(label) {
        // take only alpha num chars and append __CannedQuery
        return label.replace(/[^0-9a-z]/gi, '') + '__CannedQuery';
      }
    },
    {
      class: 'String',
      name: 'label'
    },
    {
      class: 'FObjectProperty',
      of: 'foam.mlang.predicate.Predicate',
      name: 'predicate',
      expression: function(predicateFactory) {
        return predicateFactory ? predicateFactory(foam.mlang.ExpressionsSingleton.create()) : null;
      }
    },
    {
      name: 'predicateFactory'
    }
  ]
}); 

foam.CLASS({
  package: 'foam.comics.v2',
  name: 'DAOControllerConfig', // EasyDAOController?
  properties: [
    {
      class: 'String',
      name: 'daoKey'
    },
    {
      class: 'foam.dao.DAOProperty',
      name: 'dao',
      hidden: true,
      expression: function(daoKey) {
        return this.__context__[daoKey] || foam.dao.NullDAO.create({of: foam.core.FObject});
      }
    },
    {
      class: 'Class',
      name: 'of',
      expression: function(dao$of) { return dao$of; }
    },
    {
      class: 'String',
      name: 'browseTitle',
      expression: function(of) { return of.name; }
    },
    {
      class: 'foam.u2.ViewSpecWithJava',
      name: 'browseBorder',
      expression: function() {
        // Can't use a value here because java tries to generate a HasMap
        // for it which doesn't jive with the AbstractFObjectPropertyInfo.
        return { class: 'foam.u2.borders.NullBorder' };
      }
    },
    {
      class: 'FObjectArray',
      of: 'foam.comics.v2.NamedView',
      name: 'browseViews',
      factory: null,
      expression: function(of) {
        return of.getAxiomsByClass(foam.comics.v2.NamedView);
      }
    },
    {
      class: 'FObjectArray',
      of: 'foam.comics.v2.CannedQuery',
      name: 'cannedQueries',
      factory: null,
      expression: function(of) {
        return of.getAxiomsByClass(foam.comics.v2.CannedQuery);
      }
    },
    {
      class: 'foam.u2.ViewSpecWithJava',
      name: 'viewBorder',
      expression: function() {
        // Can't use a value here because java tries to generate a HasMap
        // for it which doesn't jive with the AbstractFObjectPropertyInfo.
        return { class: 'foam.u2.borders.NullBorder' };
      }
    },
    {
      class: 'FObjectArray',
      of: 'foam.comics.v2.NamedView',
      name: 'viewViews',
      factory: function() {
        return [
          {
            name: 'SDV',
            view: { class: 'foam.u2.detail.SectionedDetailView' },
            icon: 'images/sdv-icon.svg',
          }
        ];
      }
    }
  ]
});

foam.CLASS({
  package: 'foam.comics.v2',
  name: 'DAOBrowseControllerView',
  extends: 'foam.u2.View',
  imports: [
    'stack'
  ],
  requires: [
    'foam.comics.v2.DAOBrowserView',
    'foam.u2.layout.Cols',
    'foam.u2.layout.Rows',
    'foam.u2.borders.CardBorder'
  ],

  css: `
    ^container {
      padding: 32px;
    }

    ^inner-table {
      padding: 0px 24px;
    }

    ^inner-table .foam-u2-view-TableView th {
      background: #ffffff
    }

    ^ .foam-u2-view-ScrollTableView-table {
      width: 100%;
    }
  `,

  properties: [
    {
      class: 'FObjectProperty',
      of: 'foam.comics.v2.DAOControllerConfig',
      name: 'data'
    },
    {
      class: 'foam.u2.ViewSpecWithJava',
      name: 'browseView',
      expression: function(data$browseViews) {
        debugger;
        return data$browseViews && data$browseViews.length
          ? data$browseViews[0].view
          : foam.u2.view.ScrollTableView
      }
    },
  ],
  actions: [
    {
      name: 'create',
      code: function() {
        if ( ! this.stack ) return;
        this.stack.push({
          class: 'foam.comics.v2.DAOCreateView',
          data$: this.data$,
        });
      }
    }
  ],
  methods: [
    function initE() {
    this.SUPER();

    var self = this;

      this.addClass(this.myClass())
      .add(this.slot(function(data, data$browseBorder, data$browseViews) {
        return self.E()
          .start(self.Rows).addClass(this.myClass('container'))
            .start(self.Cols).style({'align-items': 'center'})
              .start('h1').add(data.browseTitle$).end()
              .startContext({data: self}).add(self.CREATE).endContext()
            .end()
            .start(this.CardBorder)
              .start(data$browseBorder).addClass(this.myClass('inner-table'))
                  .callIf(data$browseViews.length === 1, function() {
                    self.browseView = data$browseViews[0].view;
                  }
              )
                .tag(self.DAOBrowserView, { data: data })
              .end()
            .end()
          .end();
      }));
    }
  ]
});

foam.CLASS({
  package: 'foam.comics.v2',
  name: 'DAOBrowserView',
  extends: 'foam.u2.View',
  requires: [
    'foam.u2.view.ScrollTableView',
    'foam.u2.layout.Cols',
    'foam.u2.layout.Rows',
    'foam.u2.search.Toolbar',
    'foam.u2.ActionView',
    'foam.u2.dialog.Popup'
  ],

  css: `
    ^ .foam-u2-ActionView-export {
      margin-left: 16px;
    }

    ^ .foam-u2-ActionView img {
      margin-right: 0;
    }

    ^top-bar {
      border-bottom: solid 1px #e7eaec;
      align-items: center;
    }

    ^query-bar {
      margin-top: 32px;
      margin-bottom: 24px;
      align-items: center;
    }

    ^toolbar {
      flex-grow: 1;
    }

    ^browse-view-container {
      margin: auto;
      padding-bottom: 72px;
    }
  `,

  imports: [
    'stack?'
  ],
  exports: [
    'dblclick'
  ],
  properties: [
    {
      class: 'FObjectProperty',
      of: 'foam.comics.v2.DAOControllerConfig',
      name: 'data'
    },
    {
      class: 'FObjectProperty',
      of: 'foam.mlang.predicate.Predicate',
      name: 'predicate',
      expression: function(data$cannedQueries) {
        return data$cannedQueries && data$cannedQueries.length 
          ? data$cannedQueries[0].predicate
          : foam.mlang.predicate.True.create();
      }
    },
    {
      class: 'foam.dao.DAOProperty',
      name: 'predicatedDAO',
      expression: function(data, predicate) {
        return data.dao$proxy.where(predicate);
      }
    },
  ],
  actions: [
    {
      name: 'export',
      label: '',
      icon: 'images/export-arrow-icon.svg',
      code: function() {
        this.add(this.Popup.create().tag({
          class: 'foam.u2.ExportModal',
          exportData: this.predicatedDAO
        }));
      }
    }
  ],
  methods: [
    function dblclick(obj) {
      if ( ! this.stack ) return;
      this.stack.push({
        class: 'foam.comics.v2.DAOUpdateView',
        data$: this.data$,
        obj: obj
      });
    },
    function initE() {
      var self = this;
      this.addClass(this.myClass());
      this.SUPER();
      this
        .add(self.slot(function(data$cannedQueries) {
          return self.E()
            .start(self.Rows)
              .callIf(data$cannedQueries.length >= 1, function() {
                this.start(self.Cols).addClass(self.myClass('top-bar'))
                  .start(self.Cols)
                    .callIf(data$cannedQueries.length > 1, function() {
                        this.tag( foam.u2.view.TabChoiceView, { 
                            choices: data$cannedQueries.map(o => [o.predicate, o.label]),
                            data$: self.predicate$
                          }
                        )
                    })
                    .callIf(data$cannedQueries.length === 1, function() {
                        self.predicate = data$cannedQueries[0].predicate;
                      }
                    )
                    /**
                     * otherwise if no cannedQueries are specified then the default
                     * will show all entries so that we do not break history code
                     */
                  .end()
                .end()
              })
              .start(self.Cols).addClass(this.myClass('query-bar'))
                .start().addClass(this.myClass('toolbar'))
                  .tag(self.Toolbar, { /* data$: self.predicate$ */ })
                .end()
                .startContext({data: self}).tag(self.EXPORT, {
                  buttonStyle: foam.u2.ButtonStyle.SECONDARY
                })
                .endContext()
              .end()
              .start().addClass(this.myClass('browse-view-container'))
                .tag(self.ScrollTableView, { data: self.predicatedDAO$proxy })
              .end()
            .end();
        }));
    }
  ]
}); 

foam.CLASS({
  package: 'foam.comics.v2',
  name: 'DAOUpdateView',
  extends: 'foam.u2.View',

  css:`
    ^ {
      padding: 32px
    }

    ^ .foam-u2-ActionView-back {
      display: flex;
      align-items: center;
    }

    ^account-name {
      font-size: 36px;
      font-weight: 600;
    }

    ^actions-header .foam-u2-ActionView {
      margin-right: 24px;
      line-height: 1.5
    }

    ^view-container {
      margin: auto;
    }
  `,

  requires: [
    'foam.u2.layout.Cols',
    'foam.u2.layout.Rows',
    'foam.u2.ControllerMode',
  ],
  imports: [
    'stack'
  ],
  exports: [
    'controllerMode'
  ],
  properties: [
    {
      class: 'FObjectProperty',
      of: 'foam.comics.v2.DAOControllerConfig',
      name: 'data'
    },
    {
      name: 'controllerMode',
      factory: function() {
        return this.ControllerMode.VIEW;
      }
    },
    {
      class: 'FObjectProperty',
      name: 'obj'
    },
    {
      class: 'foam.u2.ViewSpecWithJava',
      name: 'viewView',
      expression: function(data$viewViews) {
        return data$viewViews[0].view;
      }
    },
    {
      name: 'primary',
      expression: function(data$of){
        var allActions = data$of.getAxiomsByClass(foam.core.Action)
        var defaultAction = allActions.filter(a => a.isDefault);
        return defaultAction.length >= 1 ? defaultAction[0] : allActions[0];
      }
    }
  ],
  actions: [
    {
      name: 'edit',
      code: function() {
        this.controllerMode = this.ControllerMode.EDIT;
      }
    },
    {
      name: 'delete',
      code: function() {
        alert('TODO');
      }
    }
  ],
  methods: [
    function initE() {
      var self = this;
      this.SUPER();
      this
        .addClass(this.myClass())
        .add(self.slot(function(obj, data$viewBorder, data$viewViews) {
          return self.E()
            .start(self.Rows)
              .start(self.Rows)
                // we will handle this in the StackView instead
                .startContext({ data: self.stack })
                    .tag(self.stack.BACK, {
                      buttonStyle: foam.u2.ButtonStyle.TERTIARY,
                      icon: 'images/back-icon.svg'
                    })
                .endContext()
                .start(self.Cols).style({ 'align-items': 'center' })
                  .start()
                    .add(obj.toSummary())
                      .addClass(this.myClass('account-name'))
                  .end()
                  .startContext({data: obj}).add(self.primary).endContext()
                .end()
              .end()

              .start(self.Cols)
                .start(self.Cols).addClass(this.myClass('actions-header'))
                  .startContext({data: self}).tag(self.EDIT, {
                    buttonStyle: foam.u2.ButtonStyle.TERTIARY,
                    icon: 'images/edit-icon.svg'
                  }).endContext()
                  .startContext({data: self}).tag(self.DELETE, {
                    buttonStyle: foam.u2.ButtonStyle.TERTIARY,
                    icon: 'images/delete-icon.svg'
                  }).endContext()
                .end()
                .start(self.Cols)
                  .callIf(data$viewViews.length > 1, function() {
                    this.tag( foam.u2.view.IconChoiceView, { 
                        choices: data$viewViews.map(o => [o.view, o.icon]),
                        data$: self.viewView$,
                      }
                    )
                  })
                .end()
              .end()

              .start(data$viewBorder)
                .start().addClass(this.myClass('view-container'))
                  .add(self.slot(function(viewView) {
                    return self.E().tag(viewView, {
                      data: obj
                    });
                  }))
                .end()
              .end()
            .end();
        }));
    }
  ]
});

foam.CLASS({
  package: 'foam.comics.v2',
  name: 'DAOCreateView',
  extends: 'foam.u2.View',

  css:`
    ^ {
      padding: 32px
    }

    ^ .foam-u2-ActionView-back {
      display: flex;
      align-items: center;
    }

    ^account-name {
      font-size: 36px;
      font-weight: 600;
    }

    ^create-view-container {
      margin: auto;
    }
  `,

  requires: [
    'foam.u2.layout.Cols',
    'foam.u2.layout.Rows',
    'foam.u2.ControllerMode',
  ],
  imports: [
    'stack'
  ],
  exports: [
    'controllerMode'
  ],
  properties: [
    {
      class: 'FObjectProperty',
      of: 'foam.comics.v2.DAOControllerConfig',
      name: 'data'
    },
    {
      name: 'controllerMode',
      factory: function() {
        return this.ControllerMode.CREATE;
      }
    },
    {
      class: 'foam.u2.ViewSpecWithJava',
      name: 'viewView',
      expression: function(data$viewViews) {
        return data$viewViews[0].view;
      }
    }
  ],
  methods: [
    function initE() {
      var self = this;
      this.SUPER();
      this
        .addClass(this.myClass())
        .add(self.slot(function(data$viewBorder, data$browseTitle, data$of) {
          return self.E()
            .start(self.Rows)
              .start(self.Rows)
                // we will handle this in the StackView instead
                .startContext({ data: self.stack })
                    .tag(self.stack.BACK, {
                      buttonStyle: foam.u2.ButtonStyle.TERTIARY,
                      icon: 'images/back-icon.svg'
                    })
                .endContext()
                .start(self.Cols).style({ 'align-items': 'center' })
                  .start()
                    .add(`Create your ${data$browseTitle}`)
                      .addClass(this.myClass('account-name'))
                  .end()
                .end()
              .end()
              .start(data$viewBorder)
                .start().addClass(this.myClass('create-view-container'))
                  .tag(foam.u2.detail.SectionedDetailView, { data: data$of.create() })
                .end()
              .end()
        }));
    }
  ]
});