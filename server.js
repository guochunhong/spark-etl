const express = require('express');
const path = require('path');
const Pool = require('pg').Pool
const bodyParser = require('body-parser')

// DB credentials
const database = process.env.POSTGRES_DB || 'docker';
const user = process.env.POSTGRES_USER || 'docker';
const password = process.env.POSTGRES_PASSWORD || 'docker';
const host = process.env.POSTGRES_HOST || 'localhost';
const port = process.env.POSTGRES_PORT || 5432;
const authToken = process.env.AUTH_TOKEN || null;

const pool = new Pool({
  user,
  host,
  database,
  password,
  port,
});

pool.on('connect', (client) => {
  client.query(`SET search_path TO "meta"`);
});

const app = express();
app.use(bodyParser.json());
app.use(express.static(path.join(__dirname, 'build')));

app.get('/schema', function (req, res) {
 return res.json({tables: tablesData, links});
});


app.get('/databases', (req, res) => {
  pool.query('SELECT ids_database AS id, lib_database AS name FROM meta_database WHERE COALESCE(is_active_m, is_active) ORDER BY lib_database ASC', (error, results) => {
    if (error) {
      console.log('An error occured when fetching schemas:', error);
    } else {
      res.json(results.rows);
    }
  });
});


app.get('/schemas', function (req, res) {
  pool.query({
    text: 'SELECT ids_schema AS id, \
           lib_schema AS name \
           FROM meta_schema \
           WHERE ids_database = $1 \
           AND COALESCE(is_active_m, is_active) \
           ORDER BY lib_schema ASC',
    values: [req.query.ids_database]
  }, (error, results) => {
    if (error) {
      console.log('An error occured when fetching schemas:', error);
    } else {
      res.json(results.rows);
    }
  });
});


function createLinks(references, tableNameIndex) {
  const linkSet = {};
  for (const ref of references) {
    const sourceTableId = tableNameIndex[ref.table_source].id;
    const targetTableId = tableNameIndex[ref.table_target].id;
    // Do not add a link if it already exists in the other way
    if (linkSet.hasOwnProperty(targetTableId) && linkSet[targetTableId].has(sourceTableId)) {
      continue;
    }

    if (!linkSet.hasOwnProperty(sourceTableId)) {
      linkSet[sourceTableId] = new Set();
    }
    linkSet[sourceTableId].add(targetTableId);
  }

  const links = [];
  for (const source in linkSet) {
    for (const target of linkSet[source]) {
      links.push({source, target});
    }
  }

  return links;
}


function getColumns(rows, skip, editableColumns) {
  if (rows.length === 0) {
    return [];
  }
  const columns = [];
  const colNames = Object.keys(rows[0]);

  for (let i = 0; i < colNames.length; i++ ) {
    const name = colNames[i];
    if (name.startsWith('_') || (skip && skip.includes(name))) {
      continue;
    }

    const editable = editableColumns.indexOf(name) !== -1;
    columns.push({name: name, editable: editable});
  }

  return columns;
}


const allowedColumnNames = ['comment_fonctionnel', 'comment_technique', 'typ_column'];
const allowedTableColumnNames = ['comment_fonctionnel', 'comment_technique', 'typ_table'];

app.get('/tables', (req, res) => {
  Promise.all([
    pool.query({
      text: 'SELECT ids_table AS id, \
            COALESCE(lib_table_m, lib_table) AS name, \
            COALESCE(comment_fonctionnel_m, comment_fonctionnel) AS comment_fonctionnel, \
            COALESCE(comment_technique_m, comment_technique) AS comment_technique, \
            COALESCE(typ_table_m, typ_table) AS typ_table \
            FROM meta_table \
            WHERE ids_schema = $1 \
            AND COALESCE(is_active_m, is_active) \
            ORDER BY name ASC',
      values: [req.query.ids_schema]
    }),
    pool.query({
      text: 'SELECT mc.ids_column AS id, \
            COALESCE(mc.lib_column_m, mc.lib_column) AS name, \
            mc.ids_table AS ids_table, \
            COALESCE(mc.comment_fonctionnel_m, mc.comment_fonctionnel) AS comment_fonctionnel, \
            COALESCE(mc.comment_technique_m, mc.comment_technique) AS comment_technique, \
            COALESCE(mc.typ_column_m, mc.typ_column) AS typ_column, \
            COALESCE(mc.is_mandatory_m, mc.is_mandatory) AS is_mandatory, \
            COALESCE(mc.is_pk_m, mc.is_pk) AS is_pk, \
            COALESCE(mc.is_fk_m, mc.is_fk) AS is_fk \
            FROM meta_column mc, meta_table mt \
            WHERE mt.ids_table = mc.ids_table \
            AND mt.ids_schema = $1 \
            AND COALESCE(mc.is_active_m, mc.is_active) \
            AND COALESCE(mt.is_active_m, mt.is_active) \
            ORDER BY mc.ids_table, name ASC',
      values: [req.query.ids_schema]
    }),
    pool.query({
      text: 'SELECT ids_reference AS id, \
            COALESCE(lib_reference_m, lib_reference) AS name, \
            COALESCE(lib_table_source_m, lib_table_source) AS table_source, \
            COALESCE(lib_table_target_m, lib_table_target) AS table_target, \
            COALESCE(typ_table_m, typ_table) AS typ_table \
            FROM meta_reference mr, meta_column mc, meta_table mt \
            WHERE mt.ids_table = mc.ids_table \
            AND mc.ids_column = mr.ids_source \
            AND ids_schema = $1 \
            AND COALESCE(mr.is_active_m, mr.is_active) \
            AND COALESCE(mc.is_active_m, mc.is_active) \
            AND COALESCE(mt.is_active_m, mt.is_active) \
            ORDER BY mc.ids_table, name ASC',
        values: [req.query.ids_schema]
    })
  ])
    .then(results => {
      const tables = results[0].rows;
      const columns = results[1].rows;
      const references = results[2].rows;

      const tableIndex = {};
      const tableNameIndex = {};
      for (const table of tables) {
        tableIndex[table.id] = table;
        tableNameIndex[table.name] = table;
        table.columns = [];
      }

      for (const column of columns) {
        tableIndex[column.ids_table].columns.push(column);
      }

      for (const table of tables) {
        table.columns_count = table.columns.length;
      }

      res.json({
        tables: tables,
        links: createLinks(references, tableNameIndex),
        tableHeaders: getColumns(tables, ['columns', 'id'], allowedTableColumnNames),
        attributeCols: (tables.length > 0 ? getColumns(tables[0].columns, ['id', 'ids_table'], allowedColumnNames) : [])
      });
    })
    .catch(err => {
      console.log('An error occured when fetching tables:', err);
    });
});

app.get('/', function (req, res) {
  res.sendFile(path.join(__dirname, 'build', 'index.html'));
});


function checkAuth({ authorization }) {
  if (!authorization) {
    return false;
  }
  return authorization === 'Bearer ' + authToken;
}


app.post('/columns', function (req, res) {
  if (!checkAuth(req.headers)) {
    console.log(`Unauthorized access updating columns for token: ${req.headers.authorization}`);
    res.status(403).send({message: 'Unauthorized'});
    return;
  }

  if (allowedColumnNames.indexOf(req.body.colName) === -1) {
    res.status(500).send({message: 'unknown column ' + req.body.colName});
    return;
  }
  let colName = req.body.colName === 'name' ? 'lib_column' : req.body.colName;
  colName += '_m';
  pool.query({
    text: `UPDATE meta_column set ${colName} = $1 where ids_column = $2`,
      values: [req.body.text, req.body.id]
  }).then( _ => {
    res.json({message: 'ok'});
  }).catch( err => {
    console.log('An error occured updating columns', err);
    res.status(500).send({err});
  });
});

app.post('/tables', function (req, res) {
  if (!checkAuth(req.headers)) {
    console.log(`Unauthorized access updating tables for token: ${req.headers.authorization}`);
    res.status(403).send({message: 'Unauthorized'});
    return;
  }

  if (allowedTableColumnNames.indexOf(req.body.colName) === -1) {
    res.status(500).send({message: 'unknown column ' + req.body.colName});
    return;
  }
  let colName = req.body.colName === 'name' ? 'lib_table' : req.body.colName;
  colName += '_m';
  pool.query({
    text: `UPDATE meta_table set ${colName} = $1 where ids_table = $2`,
      values: [req.body.text, req.body.id]
  }).then( _ => {
    res.json({message: 'ok'});
  }).catch( err => {
    console.log('An error occured updating tables', err);
    res.status(500).send({err});
  });
  // res.sendFile(path.join(__dirname, 'build', 'index.html'));
});

const serverPort = process.env.PORT || 8080;
console.log(`server running on port: ${serverPort} with auth token: "${authToken}"`);
app.listen(serverPort);