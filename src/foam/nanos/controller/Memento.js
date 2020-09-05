/**
 * @license
 * Copyright 2020 The FOAM Authors. All Rights Reserved.
 * http://www.apache.org/licenses/LICENSE-2.0
 */

foam.CLASS({
  package: 'foam.nanos.controller',
  name: 'Memento',

  constants: {
    SEPARATOR: '#'
  },

  properties: [
    {
      class: 'Boolean',
      name: 'feedback_'
    },
    {
      name: 'value',
      value: '',
      postSet: function(o, n) {
        console.log('**************** memento: ', n);
        if ( this.feedback_ ) return;
        this.feedback_ = true;
        var i = n.indexOf(this.SEPARATOR);
        if ( i == -1 ) {
          this.head = n;
          this.tail = '';
        } else {
          this.head = n.substring(0, i);
          this.tail = n.substring(i+1);
        }
        this.feedback_ = false;
      }
    },
    {
      name: 'head',
      value: '',
      postSet: function(o, n) {
        console.log('**************** head: ', n);
        if ( this.feedback_ ) return;
        this.feedback_ = true;
        debugger;
        this.value = this.combine(n, this.tail);
        this.feedback_ = false;
      }
    },
    {
      name: 'tail',
      value: '',
      postSet: function(o, n) {
        console.log('**************** tail: ', n);
        if ( this.feedback_ ) return;
        this.feedback_ = true;
        this.value = this.combine(this.head, n);
        this.feedback_ = false;
      }
    }
  ],

  methods: [
    function combine(head, tail) {
      console.log('*************************** combine', head, tail);
      return tail ?
        head + this.SEPARATOR + tail :
        head ;
    }
  ]
});